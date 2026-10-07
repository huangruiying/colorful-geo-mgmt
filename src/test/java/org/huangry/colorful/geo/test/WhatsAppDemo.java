package org.huangry.colorful.geo.test;


import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.Whatsapp;

public class WhatsAppDemo {
    public static void main(String[] args) {
        // 创建连接并配置监听器
        Whatsapp api = Whatsapp.webBuilder() // Use the Web api
                .newConnection() // Create a new connection
                .unregistered(QrHandler.toTerminal()) // Print the QR to the terminal
                .addLoggedInListener(onLoggedIn -> System.out.printf("Connected: %s%n", onLoggedIn.store().privacySettings())) // Print a message when connected
                .addDisconnectedListener(reason -> System.out.printf("Disconnected: %s%n", reason)) // Print a message when disconnected
                .addNewMessageListener(message -> System.out.printf("New message: %s%n", message.toJson())) // Print a message when a new chat message arrives
                .connect() // Connect to Whatsapp asynchronously
                .join();// Await the result


        api.awaitDisconnection(); // Wait

        // api.sendMessage()


    }
}