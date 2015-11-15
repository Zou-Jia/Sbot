package com.ullink.slack.simpleslackapi.impl;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.message.BasicNameValuePair;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import com.google.common.io.CharStreams;
import com.ullink.slack.simpleslackapi.*;
import com.ullink.slack.simpleslackapi.events.*;
import com.ullink.slack.simpleslackapi.listeners.*;
import com.ullink.slack.simpleslackapi.impl.SlackChatConfiguration.Avatar;

class SlackWebSocketSessionImpl extends AbstractSlackSessionImpl implements SlackSession, MessageHandler.Whole<String>
{
    public class EventDispatcher
    {

        void dispatch(SlackEvent event)
        {
            switch (event.getEventType())
            {
                case SLACK_CHANNEL_ARCHIVED:
                    dispatchImpl((SlackChannelArchived) event, channelArchiveListener);
                    break;
                case SLACK_CHANNEL_CREATED:
                    dispatchImpl((SlackChannelCreated) event, channelCreateListener);
                    break;
                case SLACK_CHANNEL_DELETED:
                    dispatchImpl((SlackChannelDeleted) event, channelDeleteListener);
                    break;
                case SLACK_CHANNEL_RENAMED:
                    dispatchImpl((SlackChannelRenamed) event, channelRenamedListener);
                    break;
                case SLACK_CHANNEL_UNARCHIVED:
                    dispatchImpl((SlackChannelUnarchived) event, channelUnarchiveListener);
                    break;
                case SLACK_GROUP_JOINED:
                    dispatchImpl((SlackGroupJoined) event, groupJoinedListener);
                    break;
                case SLACK_MESSAGE_DELETED:
                    dispatchImpl((SlackMessageDeleted) event, messageDeletedListener);
                    break;
                case SLACK_MESSAGE_POSTED:
                    dispatchImpl((SlackMessagePosted) event, messagePostedListener);
                    break;
                case SLACK_MESSAGE_UPDATED:
                    dispatchImpl((SlackMessageUpdated) event, messageUpdatedListener);
                    break;
                case SLACK_REPLY:
                    dispatchImpl((SlackReplyEvent) event, slackReplyListener);
                    break;
                case SLACK_CONNECTED:
                    dispatchImpl((SlackConnected) event, slackConnectedLinster);
                    break;
                case UNKNOWN:
                    throw new IllegalArgumentException("event not handled " + event);
            }
        }

        private <E extends SlackEvent, L extends SlackEventListener<E>> void dispatchImpl(E event, List<L> listeners)
        {
            for (L listener : listeners)
            {
                listener.onEvent(event, SlackWebSocketSessionImpl.this);
            }
        }
    }


    private static final String               SLACK_HTTPS_AUTH_URL       = "https://slack.com/api/rtm.start?token=";

    private Session                           websocketSession;
    private String                            authToken;
    private String                            proxyAddress;
    private int                               proxyPort                  = -1;
    HttpHost                                  proxyHost;
    private long                              lastPingSent               = 0;
    private volatile long                     lastPingAck                = 0;

    private long                              messageId                  = 0;

    private long                              lastConnectionTime         = -1;

    private boolean                           reconnectOnDisconnection;

    private Map<Long, SlackMessageHandleImpl> pendingMessageMap          = new ConcurrentHashMap<Long, SlackMessageHandleImpl>();

    private Thread                            connectionMonitoringThread = null;
    private EventDispatcher                   dispatcher                 = new EventDispatcher();

    SlackWebSocketSessionImpl(String authToken, Proxy.Type proxyType, String proxyAddress, int proxyPort, boolean reconnectOnDisconnection)
    {
        this.authToken = authToken;
        this.proxyAddress = proxyAddress;
        this.proxyPort = proxyPort;
        this.proxyHost = new HttpHost(proxyAddress, proxyPort);
        this.reconnectOnDisconnection = reconnectOnDisconnection;
    }

    SlackWebSocketSessionImpl(String authToken, boolean reconnectOnDisconnection)
    {
        this.authToken = authToken;
        this.reconnectOnDisconnection = reconnectOnDisconnection;
    }

    @Override
    public void connect() throws IOException
    {
        connectImpl();
//        LOGGER.debug("starting connection monitoring");
        startConnectionMonitoring();
    } 

    private void connectImpl() throws IOException, ClientProtocolException, ConnectException
    {
//        LOGGER.info("connecting to slack");
        lastPingSent = 0;
        lastPingAck = 0;
        HttpClient httpClient = getHttpClient();
        HttpGet request = new HttpGet(SLACK_HTTPS_AUTH_URL + authToken);
        HttpResponse response;
        response = httpClient.execute(request);
//        LOGGER.debug(response.getStatusLine().toString());
        String jsonResponse = CharStreams.toString(new InputStreamReader(response.getEntity().getContent()));
        SlackJSONSessionStatusParser sessionParser = new SlackJSONSessionStatusParser(jsonResponse);
        try
        {
            sessionParser.parse();
        }
        catch (ParseException e1)
        {
        }
        if (sessionParser.getError() != null)
        {
            throw new ConnectException(sessionParser.getError());
        }
        users = sessionParser.getUsers();
        bots = sessionParser.getBots();
        channels = sessionParser.getChannels();
        sessionPersona = sessionParser.getSessionPersona();
//        LOGGER.info(users.size() + " users found on this session");
//        LOGGER.info(bots.size() + " bots found on this session");
//        LOGGER.info(channels.size() + " channels found on this session");
        String wssurl = sessionParser.getWebSocketURL();

//        LOGGER.debug("retrieved websocket URL : " + wssurl);
        ClientManager client = ClientManager.createClient();
        client.getProperties().put(ClientProperties.LOG_HTTP_UPGRADE, true);
        if (proxyAddress != null)
        {
            client.getProperties().put(ClientProperties.PROXY_URI, "http://" + proxyAddress + ":" + proxyPort);
        }
        final MessageHandler handler = this;
//        LOGGER.debug("initiating connection to websocket");
        try
        {
            websocketSession = client.connectToServer(new Endpoint()
            {
                @Override
                public void onOpen(Session session, EndpointConfig config)
                {
                    session.addMessageHandler(handler);
                }

            }, URI.create(wssurl));
        }
        catch (DeploymentException e)
        {
        }
        if (websocketSession != null)
        {
            SlackConnectedImpl slackConnectedImpl = new SlackConnectedImpl(sessionPersona);
            dispatcher.dispatch(slackConnectedImpl);
        }
    }

    private void startConnectionMonitoring()
    {
        connectionMonitoringThread = new Thread()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    try
                    {
                        // heart beat of 30s (should be configurable in the future)
                        Thread.sleep(30000);
                        if (lastPingSent != lastPingAck || websocketSession == null)
                        {
                            // disconnection happened
                            try
                            {
                                if (websocketSession != null)
                                {
                                    websocketSession.close();
                                }
                            }
                            catch (IOException e)
                            {
                            }
                            websocketSession = null;
                            if (reconnectOnDisconnection)
                            {
                                connectImpl();
                                continue;
                            }
                            else
                            {
                                this.interrupt();
                            }
                        }
                        else
                        {
                            lastPingSent = getNextMessageId();
                            try
                            {
                                if (websocketSession.isOpen())
                                {
                                    websocketSession.getBasicRemote().sendText("{\"type\":\"ping\",\"id\":" + lastPingSent + "}");
                                }
                                else if (reconnectOnDisconnection)
                                {
                                    connectImpl();
                                }
                            }
                            catch (IllegalStateException e)
                            {
                                // websocketSession might be closed in this case
                                if (reconnectOnDisconnection)
                                {
                                    connectImpl();
                                }
                            }
                        }
                    }
                    catch (InterruptedException e)
                    {
                        break;
                    }
                    catch (IOException e)
                    {
                    }
                }
            }
        };
        connectionMonitoringThread.start();
    }

    @Override
    public SlackMessageHandle sendMessage(SlackChannel channel, String message, SlackAttachment attachment, SlackChatConfiguration chatConfiguration)
    {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        HttpClient client = getHttpClient();
        HttpPost request = new HttpPost("https://slack.com/api/chat.postMessage");
        List<NameValuePair> nameValuePairList = new ArrayList<>();
        nameValuePairList.add(new BasicNameValuePair("token", authToken));
        nameValuePairList.add(new BasicNameValuePair("channel", channel.getId()));
 //       if (chatConfiguration.asUser)
        {
            nameValuePairList.add(new BasicNameValuePair("as_user", "true"));
        }
        nameValuePairList.add(new BasicNameValuePair("text", message));
        if (chatConfiguration.avatar == Avatar.ICON_URL)
        {
            nameValuePairList.add(new BasicNameValuePair("icon_url", chatConfiguration.avatarDescription));
        }
        if (chatConfiguration.avatar == Avatar.EMOJI)
        {
            nameValuePairList.add(new BasicNameValuePair("icon_emoji", chatConfiguration.avatarDescription));
        }
        if (chatConfiguration.userName != null)
        {
            nameValuePairList.add(new BasicNameValuePair("username", chatConfiguration.userName));
        }
        if (attachment != null)
        {
            nameValuePairList.add(new BasicNameValuePair("attachments", SlackJSONAttachmentFormatter.encodeAttachments(attachment).toString()));
        }
        try
        {
            request.setEntity(new UrlEncodedFormEntity(nameValuePairList, "UTF-8"));
            HttpResponse response = client.execute(request);
            String jsonResponse = CharStreams.toString(new InputStreamReader(response.getEntity().getContent()));
            SlackReplyImpl reply = SlackJSONReplyParser.decode(parseObject(jsonResponse));
            handle.setSlackReply(reply);
        }
        catch (Exception e)
        {
            // TODO : improve exception handling
            e.printStackTrace();
        }
        return handle;
    }

    @Override
    public SlackMessageHandle deleteMessage(String timeStamp, SlackChannel channel)
    {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        HttpClient client = getHttpClient();
        HttpPost request = new HttpPost("https://slack.com/api/chat.delete");
        List<NameValuePair> nameValuePairList = new ArrayList<>();
        nameValuePairList.add(new BasicNameValuePair("token", authToken));
        nameValuePairList.add(new BasicNameValuePair("channel", channel.getId()));
        nameValuePairList.add(new BasicNameValuePair("ts", timeStamp));
        try
        {
            request.setEntity(new UrlEncodedFormEntity(nameValuePairList, "UTF-8"));
            HttpResponse response = client.execute(request);
            String jsonResponse = CharStreams.toString(new InputStreamReader(response.getEntity().getContent()));
            SlackReplyImpl reply = SlackJSONReplyParser.decode(parseObject(jsonResponse));
            handle.setSlackReply(reply);
        }
        catch (Exception e)
        {
            // TODO : improve exception handling
            e.printStackTrace();
        }
        return handle;
    }

    @Override
    public SlackMessageHandle updateMessage(String timeStamp, SlackChannel channel, String message)
    {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        HttpClient client = getHttpClient();
        HttpPost request = new HttpPost("https://slack.com/api/chat.update");
        List<NameValuePair> nameValuePairList = new ArrayList<>();
        nameValuePairList.add(new BasicNameValuePair("token", authToken));
        nameValuePairList.add(new BasicNameValuePair("ts", timeStamp));
        nameValuePairList.add(new BasicNameValuePair("channel", channel.getId()));
        nameValuePairList.add(new BasicNameValuePair("text", message));
        try
        {
            request.setEntity(new UrlEncodedFormEntity(nameValuePairList, "UTF-8"));
            HttpResponse response = client.execute(request);
            String jsonResponse = CharStreams.toString(new InputStreamReader(response.getEntity().getContent()));
            SlackReplyImpl reply = SlackJSONReplyParser.decode(parseObject(jsonResponse));
            handle.setSlackReply(reply);
        }
        catch (Exception e)
        {
            // TODO : improve exception handling
            e.printStackTrace();
        }
        return handle;
    }

    @Override
    public SlackMessageHandle addReactionToMessage(SlackChannel channel, String messageTimeStamp, String emojiCode)
    {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        HttpClient client = getHttpClient();
        HttpPost request = new HttpPost("https://slack.com/api/reactions.add");
        List<NameValuePair> nameValuePairList = new ArrayList<>();
        nameValuePairList.add(new BasicNameValuePair("token", authToken));
        nameValuePairList.add(new BasicNameValuePair("channel", channel.getId()));
        nameValuePairList.add(new BasicNameValuePair("timestamp", messageTimeStamp));
        nameValuePairList.add(new BasicNameValuePair("name", emojiCode));
        try
        {
            request.setEntity(new UrlEncodedFormEntity(nameValuePairList, "UTF-8"));
            HttpResponse response = client.execute(request);
            String jsonResponse = CharStreams.toString(new InputStreamReader(response.getEntity().getContent()));
            SlackReplyImpl reply = SlackJSONReplyParser.decode(parseObject(jsonResponse));
            handle.setSlackReply(reply);
        }
        catch (Exception e)
        {
            // TODO : improve exception handling
            e.printStackTrace();
        }
        return handle;
    }

    private HttpClient getHttpClient()
    {
        HttpClient client = null;
        if (proxyHost != null)
        {
            client = HttpClientBuilder.create().setRoutePlanner(new DefaultProxyRoutePlanner(proxyHost)).build();
        }
        else
        {
            client = HttpClientBuilder.create().build();
        }
        return client;
    }

    @Override
    public SlackMessageHandle sendMessageOverWebSocket(SlackChannel channel, String message, SlackAttachment attachment)
    {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        try
        {
            JSONObject messageJSON = new JSONObject();
            messageJSON.put("type", "message");
            messageJSON.put("channel", channel.getId());
            messageJSON.put("text", message);
            if (attachment != null)
            {
                messageJSON.put("attachments", SlackJSONAttachmentFormatter.encodeAttachments(attachment));
            }
            websocketSession.getBasicRemote().sendText(messageJSON.toJSONString());
        }
        catch (Exception e)
        {
            // TODO : improve exception handling
            e.printStackTrace();
        }
        return handle;
    }

    @Override
    public SlackPersona.SlackPresence getPresence(SlackPersona persona)
    {
        HttpClient client = getHttpClient();
        HttpPost request = new HttpPost("https://slack.com/api/users.getPresence");
        List<NameValuePair> nameValuePairList = new ArrayList<>();
        nameValuePairList.add(new BasicNameValuePair("token", authToken));
        nameValuePairList.add(new BasicNameValuePair("user", persona.getId()));
        try
        {
            request.setEntity(new UrlEncodedFormEntity(nameValuePairList, "UTF-8"));
            HttpResponse response = client.execute(request);
            String jsonResponse = CharStreams.toString(new InputStreamReader(response.getEntity().getContent()));
            JSONObject resultObject = parseObject(jsonResponse);

            SlackReplyImpl reply = SlackJSONReplyParser.decode(resultObject);
            if (!reply.isOk())
            {
                return SlackPersona.SlackPresence.UNKNOWN;
            }
            String presence = (String) resultObject.get("presence");

            if ("active".equals(presence))
            {
                return SlackPersona.SlackPresence.ACTIVE;
            }
            if ("away".equals(presence))
            {
                return SlackPersona.SlackPresence.AWAY;
            }
        }
        catch (Exception e)
        {
            // TODO : improve exception handling
            e.printStackTrace();
        }
        return SlackPersona.SlackPresence.UNKNOWN;
    }

    private synchronized long getNextMessageId()
    {
        return messageId++;
    }

    @Override
    public void onMessage(String message)
    {
        if (message.contains("{\"type\":\"pong\",\"reply_to\""))
        {
            int rightBracketIdx = message.indexOf('}');
            String toParse = message.substring(26, rightBracketIdx);
            lastPingAck = Integer.parseInt(toParse);
        }
        else
        {
            JSONObject object = parseObject(message);
            SlackEvent slackEvent = SlackJSONMessageParser.decode(this, object);
            if (slackEvent instanceof SlackChannelCreated)
            {
                SlackChannelCreated slackChannelCreated = (SlackChannelCreated) slackEvent;
                channels.put(slackChannelCreated.getSlackChannel().getId(), slackChannelCreated.getSlackChannel());
            }
            if (slackEvent instanceof SlackGroupJoined)
            {
                SlackGroupJoined slackGroupJoined = (SlackGroupJoined) slackEvent;
                channels.put(slackGroupJoined.getSlackChannel().getId(), slackGroupJoined.getSlackChannel());
            }
            dispatcher.dispatch(slackEvent);
        }
    }

    private JSONObject parseObject(String json)
    {
        JSONParser parser = new JSONParser();
        try
        {
            JSONObject object = (JSONObject) parser.parse(json);
            return object;
        }
        catch (ParseException e)
        {
            e.printStackTrace();
            return null;
        }
    }

}
