package com.ullink.slack.simpleslackapi.impl;

import java.util.HashMap;
import java.util.Map;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.ullink.slack.simpleslackapi.SlackBot;
import com.ullink.slack.simpleslackapi.SlackChannel;
import com.ullink.slack.simpleslackapi.SlackUser;
import com.ullink.slack.simpleslackapi.SlackPersona;

class SlackJSONSessionStatusParser
{

    private Map<String, SlackChannel> channels = new HashMap<>();
    private Map<String, SlackUser>    users    = new HashMap<>();
    private Map<String, SlackBot>     bots     = new HashMap<>();

    private SlackPersona sessionPersona;

    private String                    webSocketURL;

    private String                    toParse;

    private String                    error;

    SlackJSONSessionStatusParser(String toParse)
    {
        this.toParse = toParse;
    }

    Map<String, SlackBot> getBots()
    {
        return bots;
    }

    Map<String, SlackChannel> getChannels()
    {
        return channels;
    }

    Map<String, SlackUser> getUsers()
    {
        return users;
    }

    public String getWebSocketURL()
    {
        return webSocketURL;
    }

    public String getError()
    {
        return error;
    }
    
    void parse() throws ParseException
    {
        JSONParser parser = new JSONParser();
        JSONObject jsonResponse = (JSONObject) parser.parse(toParse);
        Boolean ok = (Boolean)jsonResponse.get("ok");
        if (Boolean.FALSE.equals(ok)) {
            error = (String)jsonResponse.get("error");
            return;
        }
        JSONArray usersJson = (JSONArray) jsonResponse.get("users");

        for (Object jsonObject : usersJson)
        {
            JSONObject jsonUser = (JSONObject) jsonObject;
            SlackUser slackUser = SlackJSONParsingUtils.buildSlackUser(jsonUser);
            users.put(slackUser.getId(), slackUser);
        }

        JSONArray botsJson = (JSONArray) jsonResponse.get("bots");

        for (Object jsonObject : botsJson)
        {
            JSONObject jsonBot = (JSONObject) jsonObject;
            SlackBot slackBot = SlackJSONParsingUtils.buildSlackBot(jsonBot);
            bots.put(slackBot.getId(), slackBot);
        }

        JSONArray channelsJson = (JSONArray) jsonResponse.get("channels");

        for (Object jsonObject : channelsJson)
        {
            JSONObject jsonChannel = (JSONObject) jsonObject;
            SlackChannelImpl channel = SlackJSONParsingUtils.buildSlackChannel(jsonChannel, users);
            channels.put(channel.getId(), channel);
        }

        JSONArray groupsJson = (JSONArray) jsonResponse.get("groups");

        for (Object jsonObject : groupsJson)
        {
            JSONObject jsonChannel = (JSONObject) jsonObject;
            SlackChannelImpl channel = SlackJSONParsingUtils.buildSlackChannel(jsonChannel, users);
            channels.put(channel.getId(), channel);
        }

        JSONObject selfJson = (JSONObject) jsonResponse.get("self");
        sessionPersona = SlackJSONParsingUtils.buildSlackUser(selfJson);


        webSocketURL = (String) jsonResponse.get("url");

    }

    public SlackPersona getSessionPersona() {
        return sessionPersona;
    }
}
