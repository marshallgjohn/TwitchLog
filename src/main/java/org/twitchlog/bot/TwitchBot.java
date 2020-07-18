package org.twitchlog.bot;


import com.google.gson.Gson;
import com.opencsv.CSVWriter;
import org.twitchlog.objects.Channel;
import org.twitchlog.objects.TopViewerPage;
import org.twitchlog.TwitchLog;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class TwitchBot extends Bot {

    private Statement statement;
    private ArrayList<Channel> chatChannels;
    private ArrayList<String> messages = new ArrayList<>();

    private String username;
    private String oauth;
    private int VIEWER_THRESHOLD = 100;
    private int MAX_MESSAGE_TRIGGER = 5000;
    private int MIN_MESSAGE_TRIGGER = 1000;
    private int MAX_BOT_CHANNELS = 30;
    private double MESSAGE_WEIGHT = 2;
    private int ADD_ADDITIONAL_CHANNEL_DELAY = 300;



    private int CHANNEL_COUNT;
    private boolean RESTART_BOOL;
    private TwitchBot ADDITIONAL_CHANNEL = null;

    //TODO make the max channels increase as viewers go down
    //TODO look into better db solution than mysql?
    //TODO implement GUI?
    //TODO refactor code and make more efficient and abstracted?

    public TwitchBot(String username, String oauth, ArrayList<Channel> channels, int count, boolean restart) {
        super(username, oauth);
        this.RESTART_BOOL = restart;
        this.username = username;
        this.oauth = oauth;
        this.chatChannels = channels;
        this.CHANNEL_COUNT = count;

        this.MAX_MESSAGE_TRIGGER = descendingMessageTrigger(count);
    }

    /**
     * Pulls all channels for bot to join and connects to twitch IRC
     * then starts monitoring chat and adding it to database
     * **/
    public void connect(boolean checkChannels) {
        //Parameter determines if channels will be pulled from API
        if(checkChannels) {
            convertFromJSON(getChatChannels(null), this.chatChannels);
        }
        try {
            //Needed IRC login commands
            getWriter().write("PASS oauth:" + this.oauth + "\r\n");
            getWriter().write("NICK " + this.username + "\r\n");
            getWriter().flush();

            //Starts new thread for monitoring chat and joining channels b/c there will be multiple bots running
            new Thread(() -> {
                joinAllChannels(this.chatChannels, this.CHANNEL_COUNT,this.ADDITIONAL_CHANNEL);
                getChat();
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Converts JSON from TWITCH API to CHANNEL objects
     * and makes sure meets requirement of english and viewer count threshold
     * and recursively calls method to continue pulling data from pages till
     * channels returned are no longer above viewer threshold
     * **/
    private void convertFromJSON(String str, ArrayList<Channel> channels) {
        Gson gson = new Gson();
        TopViewerPage page = gson.fromJson(str, TopViewerPage.class);

        boolean viewer_bool = false;
        //Makes sure data is kind that is wanted
        for (Channel x : page.data) {
            if (x.language.equals("en")) {
                if (x.viewer_count >= VIEWER_THRESHOLD) {
                    channels.add(x);
                } else {
                    viewer_bool = true;
                    break;
                }
            }
        }
        //Recursive call
        if (!viewer_bool) {
            convertFromJSON(getChatChannels(page.pagination.cursor),channels);
        }
    }

    /**
     * Converts unix time received from TWITCH CHAT messages to
     * YYYY-MM-DD HH:MM:SS
     * **/
    String convertUNIX(String unix) {
        final DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        final long unixTime = Long.parseLong(unix)/1000;
        return Instant.ofEpochSecond(unixTime)
                .atZone(ZoneId.of("GMT-5"))
                .format(formatter);
    }


    /**
     * Formula to decrease amount of messages needed to write to database
     * as viewer size decrease and amount of channels increase
     * **/
    private int descendingMessageTrigger(int count) {
        int math = (int) (MAX_MESSAGE_TRIGGER - ((MAX_MESSAGE_TRIGGER - MIN_MESSAGE_TRIGGER) / 1500) * (count * MESSAGE_WEIGHT));
        
        return (math >= MIN_MESSAGE_TRIGGER) ? math : 2000;
    }


    /**
     * Disconnect from Twitch IRC
     */
    public void disconnect() {

    }


    /**
     * Converts twitch messages into parts and writes to CSV file
     * then inserts CSV file into database
     */
    private void insertData(ArrayList<String> list) {
        try {
            //Names CSV file after thread name as to not have any overwrite issues if two
            //thresholds are met at the same time
            File file = new File("log" + Thread.currentThread().getName() + ".csv");
            FileWriter outputFile = new FileWriter(file, false);
            CSVWriter writer = new CSVWriter(outputFile);
            List<String[]> data = new ArrayList<>();
            //Splits message from IRC into parts and adds to CSV file
            for (String x : list) {
                String[] values = processChat(x);
                String msg = values[0];
                String msg_time = values[1];
                String msg_username = values[2];
                String msg_channel = values[3];
                String msg_badges = values[4];
                data.add(new String[]{msg, msg_time,msg_channel, msg_username, msg_badges});
            }
            writer.writeAll(data);
            writer.close();


            try {
                //Writes CSV to database
                statement = TwitchLog.connection.createStatement();
                statement.executeQuery("LOAD DATA local INFILE " +
                        "'log" + Thread.currentThread().getName() + ".csv'" +
                        " into table twitchlogs.message" +
                        " CHARACTER SET latin1" +
                        " FIELDS TERMINATED BY ','" +
                        " ENCLOSED BY '\"'" +
                        " LINES TERMINATED BY '\n'" +
                        "" +
                        " (content,time,channel,username,badges)");
                //
            } catch (SQLException throwables) {
                throwables.printStackTrace();
            }


        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
    * Returns twitch chat and calls insert data once number of message sent equals MAX_MESSAGE_TRIGGER.
     * Also if TWITCH closes IRC connections for w/e reason it writes data to db and restarts thread.
    * */
    void getChat() {
        String line;
        try {
            System.out.println(Thread.currentThread().getName()+": Beginning processing chat...");
            while ((line = getReader().readLine()) != null) {
                //Only returns data if have PRIVMSG as they are user chat messages
                if (line.contains("PRIVMSG") && line.startsWith("@")) {
                    messages.add(line);

                    //Insert data trigger
                    if (messages.size() == MAX_MESSAGE_TRIGGER) {
                        ArrayList<String> data = new ArrayList<>(messages);
                        messages.clear();
                        insertData(data);
                    }
                    //Need to respond to PING w/ PONG in order to stay connected to IRC
                } else if (line.startsWith("PING")) {
                    getWriter().write("PONG\r\n");
                    getWriter().flush();
                }
            }
            //Restarts thread
            insertData(new ArrayList<>(messages));
            System.out.println("RESTARTING " + Thread.currentThread().getName());
            if(this.CHANNEL_COUNT - this.MAX_BOT_CHANNELS > 1) {
                new TwitchBot(this.username, this.oauth, this.chatChannels, this.CHANNEL_COUNT - this.MAX_BOT_CHANNELS, true).connect(false);
            } else {
                new TwitchBot(this.username, this.oauth, this.chatChannels, 0, true).connect(false);
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Returns channels list from TWITCH API
     * From biggest viewers to smallest
     * */
    private String getChatChannels(String token) {
        HttpsURLConnection connection = null;
        URL url;
        try {
            if (token != null) {
                url = new URL("https://api.twitch.tv/helix/streams?first=100&after=" + token);
            } else {
                url = new URL("https://api.twitch.tv/helix/streams?first=100");
            }
            HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            //Map<String, String> parameters = new HashMap<>();
            //parameters.put("first", "100");
            con.setRequestProperty("Authorization", "Bearer wtta8czwiyjxwhbwdf850mzyuuwt2z");
            con.setRequestProperty("Client-ID", "gp762nuuoqcoxypju8c569th9wz7q5");
            con.setDoOutput(false);

            int status = con.getResponseCode();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            con.disconnect();



            return content.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    public void joinAdditionalChannels() {
        while(true) {
            try {
                Thread.sleep(ADD_ADDITIONAL_CHANNEL_DELAY * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            ArrayList<Channel> newChannels = new ArrayList<>();
            ArrayList<Channel> removedList = new ArrayList<>();
            //removedList.clear();
            convertFromJSON(getChatChannels(null), newChannels);


            int count = 0;
            if (newChannels.size() > 0) {
                for(Channel newChannel: newChannels) {
                    count=0;
                    for(Channel chatChannel: this.chatChannels) {
                        if(newChannel.user_name.equalsIgnoreCase(chatChannel.user_name)){
                            count++;
                        }
                    }
                    if (count == 0) {
                        removedList.add(newChannel);
                    }
                }

                if (removedList.size() > 0) {
                    for (Channel x : removedList) {
                        this.chatChannels.add(x);
                        System.out.println("Channel Added: " + x.user_name);
                    }
                    if (ADDITIONAL_CHANNEL != null && ADDITIONAL_CHANNEL.getCHANNEL_COUNT() < MAX_BOT_CHANNELS) {
                        for (int i = 0; i < removedList.size(); i++) {
                            //TODO - make it so if removedList will overwhelm bot it splits it into a new bot
                            if(ADDITIONAL_CHANNEL.getCHANNEL_COUNT() < MAX_BOT_CHANNELS) {
                                ADDITIONAL_CHANNEL.joinChannel(removedList.get(i).user_name);
                                ADDITIONAL_CHANNEL.setCHANNEL_COUNT(ADDITIONAL_CHANNEL.getCHANNEL_COUNT()+1);
                            }
                            else {
                                ADDITIONAL_CHANNEL = new TwitchBot(this.username, this.oauth, new ArrayList<Channel>(removedList.subList(i,removedList.size())), 0, false);
                                ADDITIONAL_CHANNEL.connect(false);
                                break;
                            }
                        }
                        System.out.printf("ADDITIONAL CHANNELS: %d/%d\n",ADDITIONAL_CHANNEL.getCHANNEL_COUNT(),MAX_BOT_CHANNELS);
                    } else {
                        System.out.println("ADDITIONAL CHANNELS: Starting a new bot");
                        ADDITIONAL_CHANNEL = new TwitchBot(this.username, this.oauth, removedList, 0, false);
                        ADDITIONAL_CHANNEL.connect(false);
                        //ADDITIONAL_CHANNEL.setCHANNEL_COUNT(removedList.size());
                        System.out.println("COUNT:" + ADDITIONAL_CHANNEL.getCHANNEL_COUNT());
                    }
                } else {
                    System.out.println("No new channels to join");
                }
            } else {
                System.out.println("How did we get here?");
            }
        }
    }

    public void joinChannel(String channel) {
        try {
            getWriter().write("JOIN #" + channel.toLowerCase() + "\r\n");
            getWriter().flush();
        } catch (IOException e) {
            e.printStackTrace();
            this.ADDITIONAL_CHANNEL = null;
        }
    }

    private void joinAllChannels(ArrayList<Channel> channels, int channelCount, TwitchBot additionalBot) {
        int count = 0;
        int chatChannelCount = channels.size();

        System.out.println("CHANNEL COUNT: " + this.CHANNEL_COUNT);

        for (int i = channelCount; i < chatChannelCount; i++) {
            try {
                if (count < MAX_BOT_CHANNELS) {
                    if (count == ((MAX_BOT_CHANNELS / 2) - 1) || count == (MAX_BOT_CHANNELS - 1)) {
                        if(((count+1) % 5 == 0)) {
                            joinChannel(channels.get(i).user_name.toLowerCase());
                            this.CHANNEL_COUNT++;
                            System.out.println(Thread.currentThread().getName() + ": Too many channels joined... Waiting 30 seconds.... " + (this.CHANNEL_COUNT) + "/" + chatChannelCount);
                            count++;
                            Thread.sleep(5000);
                        }
                    } else {
                        joinChannel(channels.get(i).user_name.toLowerCase());
                        this.CHANNEL_COUNT++;
                        System.out.println(this.CHANNEL_COUNT);
                        count++;
                    }
                } else if (count == MAX_BOT_CHANNELS && !RESTART_BOOL) {
                    int finalChannelCount = this.CHANNEL_COUNT++;
                    if(additionalBot != null) {
                     additionalBot = new TwitchBot(this.username, this.oauth, channels, finalChannelCount, false);
                     additionalBot.connect(false);
                    } else {
                        new TwitchBot(this.username, this.oauth, channels, finalChannelCount, false).connect(false);
                    }
                    break;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println(Thread.currentThread().getName() + ": Finished joining");
        try {
            getWriter().write("CAP REQ :twitch.tv/tags \r\n");
            getWriter().flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    String[] processChat(String message) {
        String str[];
        String msg_badges = "none";
        str = message.split(";");
        String msg = message.split("PRIVMSG #[0-9A-Za-z_]{3,} :")[1];

        String msg_channel = message.split("PRIVMSG #")[1].split(" :")[0];
        if (!str[1].equals("badges=")) {
            msg_badges = str[1].split("badges=")[1];
        }
        String msg_username = message.split("display-name=")[1].split(";")[0];
        String msg_time = convertUNIX(message.split("tmi-sent-ts=")[1].split(";")[0]);


        return new String[]{msg,msg_time,msg_username, msg_channel, msg_badges};

    }

    public int getCHANNEL_COUNT() {
        return CHANNEL_COUNT;
    }

    public void setCHANNEL_COUNT(int CHANNEL_COUNT) {
        this.CHANNEL_COUNT = CHANNEL_COUNT;
    }
}
