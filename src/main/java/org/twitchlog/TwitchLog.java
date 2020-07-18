package org.twitchlog;

import org.twitchlog.bot.TwitchBot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;

public class TwitchLog {
public static Connection connection;

    public static void main (String[] args) {
        try {
            connection = DriverManager.getConnection("jdbc:mysql://localhost:3306/{dbname}?user={username}&password={insertpassword}$&useSSL=false&allowLoadLocalInfile=true&characterEncoding=UTF-8");
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }

        TwitchBot bot = new TwitchBot("username","authtoken",new ArrayList<>(),0, false);
        bot.connect(true);
        new Thread(bot::joinAdditionalChannels).start();
    }
}
