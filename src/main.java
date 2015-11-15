import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import com.ullink.slack.simpleslackapi.SlackMessageHandle;
import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted;
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory;
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener;

public class main {

	public static void main(String[] args) {
		// GetDataUtil.updateDatabase();
		final SlackSession session = SlackSessionFactory
				.createWebSocketSlackSession("xoxb-8196617392-Uq0VBpz11Uc9PzGoPcBW7I3g");
		try {
			session.connect();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		session.sendMessage(session.findChannelByName("general"),
				"°¡±¿±¿ Bot³ÌÐò ¿ªÊ¼À²!", null);
		
//		session.addMessagePostedListener((e, s) 
//				  -> s.sendMessageOverWebSocket(s.findChannelByName("general"), "Message sent : " + e.getMessageContent(), null));
		
		session.addMessagePostedListener(new SlackMessagePostedListener() {
			@Override
			public void onEvent(SlackMessagePosted event, SlackSession session) {
				// let's send a message
				String message = event.getMessageContent();
//				if (message.substring(0, 5).equals("echo ")) {
//					session.sendMessage(session.findChannelByName("general"),
//							message.substring(5), null);
//				} else 
				if (message.equals("time")) 
				{
					String timeStamp = new SimpleDateFormat("EEE, yyyy MMM d HH:mm:ss")
							.format(Calendar.getInstance().getTime());
					session.sendMessage(session.findChannelByName("general"),
							timeStamp, null);
				}
			}
		});
		while (true) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

	}

}
