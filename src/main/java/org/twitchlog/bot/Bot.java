package org.twitchlog.bot;

import java.io.*;
import java.net.Socket;

public abstract class Bot {

    private String username;
    private String oauth;
    private String URL = "irc.twitch.tv";
    private int port = 6667;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    public Bot(String username, String oauth) {
        this.username = username;
        this.oauth = oauth;

        try {
            this.socket = new Socket(this.URL, this.port);
            this.writer = new BufferedWriter(new OutputStreamWriter(this.socket.getOutputStream()));
            this.reader = new BufferedReader(new InputStreamReader(this.socket.getInputStream()));

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    abstract void connect(boolean checkChannel);

    abstract void disconnect();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getOauth() {
        return oauth;
    }

    public void setOauth(String oauth) {
        this.oauth = oauth;
    }


    public String getURL() {
        return URL;
    }

    public void setURL(String URL) {
        this.URL = URL;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Socket getSocket() {
        return socket;
    }

    public void setSocket(Socket socket) {
        this.socket = socket;
    }

    public BufferedReader getReader() {
        return reader;
    }

    public void setReader(BufferedReader reader) {
        this.reader = reader;
    }

    public BufferedWriter getWriter() {
        return writer;
    }

    public void setWriter(BufferedWriter writer) {
        this.writer = writer;
    }


}
