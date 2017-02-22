package server;

import client.ClientThread;
import datamodel.json.audio.JSONAudioClientUserData;
import datamodel.json.audio.JSONAudioRequestFromClient;
import datamodel.json.stream.JSONStreamClientUserData;
import datamodel.json.stream.JSONStreamRequestFromClient;
import datamodel.pojo.Message;
import datamodel.pojo.User;
import datamodel.pojo.Utils;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Lazar Davidovic
 */
public class ChatServer {

    //TCP
    static int serverPortTCP;
    static Socket serverSocketTCP = null;
    static ServerSocket serverWelcomeSoketTCP = null;

    //UDP
    //serverPortUDP not used!
    //static int serverPortUDP = 1715;
    private static List<ClientThread> clients;

    
    public static List<ClientThread> getClients() {
        return clients;
    }

    

    public ChatServer(int serverPortTCP) {
        this.serverPortTCP = serverPortTCP;
    }

    
    public void execute() {
        try {
            System.out.println("Pokretanje servera...");

            //TCP
            serverWelcomeSoketTCP = new ServerSocket(serverPortTCP);

            //User list
            clients = new ArrayList<>();

//            new BroadcastThread().start();
            
            acceptClients();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Accept clients.
     */
    private void acceptClients() {
        while (true) {
            try {
                System.out.println("Ocekivanje novog klijenta...");
                serverSocketTCP = serverWelcomeSoketTCP.accept();
                System.out.println("Prihvatanje klijenta na portu: " + serverSocketTCP.getPort());
                ClientThread client = new ClientThread(serverSocketTCP);
                System.out.println("Dodavanje klijenta u listu...");
                clients.add(client);
                client.start();
                System.out.println("Konekcija sa klijentom je prekinuta.");

            } catch (IOException ex) {
                ex.printStackTrace();
            }

        }
    }
    
    public static void fireOnlineClientLists() {
        for (ClientThread client : clients) {
            client.createAndSendOnlineUsersListToClient();

        }
    }

    public static void removeOfflineClient(ClientThread offClient) {
        clients.remove(offClient);
    }

    public static void requireAudioConnection(JSONAudioRequestFromClient audioRequest) {
        List<User> to = audioRequest.getTo();
        for (ClientThread client : clients) {
            for (User user : to) {
                if (!client.getUser().getUserName().
                        equals(audioRequest.getUserData().getUser().getUserName()) 
                                && client.getUser().getUserName().
                                        equals(user.getUserName())) {
                    client.sendAudioRequest(audioRequest);
                }
            }
        }
        System.out.println("Saljem potvrde klijenata od " + audioRequest.getUserData().getUser().getUserName());
//        sendAudioUserConfirmResponseList(audioRequest.getUserData().getUser());
    }
    
    public static void sendAudioUserConfirmResponseList(User user) {
        List<JSONAudioClientUserData> confirmedUsers = new ArrayList<>();
        for (ClientThread client : clients) {
            if (!client.getUser().getUserName().equals(user.getUserName()) && client.isAudioConfirmation()) {
                confirmedUsers.add(new JSONAudioClientUserData(client.getUser(), null));
            }
        }

//        for (JSONAudioClientUserData confirmedUser : confirmedUsers) {
            for (ClientThread client : clients) {
//                if (client.getUser().equals(confirmedUser.getUser())) {
                if (client.getUser().equals(user)) {
                    client.sendAudioResponse(confirmedUsers);
                }
            }
//        }
    }
    public static void requireStreamConnection(JSONStreamRequestFromClient jsonStreamRequestFromClient) {
        List<User> to = jsonStreamRequestFromClient.getTo();
        for (ClientThread client : clients) {
            for (User user : to) {
                if (!client.getUser().getUserName().equals(jsonStreamRequestFromClient.getUserData().getUser().getUserName()) && client.getUser().getUserName().equals(user.getUserName())) {
                    client.sendStreamRequest(jsonStreamRequestFromClient);
                }
            }
        }
        sendUserConfirmResponseList(jsonStreamRequestFromClient.getUserData().getUser());
    }

    private static void sendUserConfirmResponseList(User user) {
        List<JSONStreamClientUserData> confirmedUsers = new ArrayList<>();
        for (ClientThread client : clients) {
            if (!client.getUser().equals(user) && client.isStreamingConfirmation()) {
                confirmedUsers.add(new JSONStreamClientUserData(client.getUser(), null));
            }
        }

        for (JSONStreamClientUserData confirmedUser : confirmedUsers) {
            for (ClientThread client : clients) {
                if (client.getUser().equals(confirmedUser.getUser())) {
                    client.sendStreamResponse(confirmedUsers);
                }
            }
        }
    }
     
}
