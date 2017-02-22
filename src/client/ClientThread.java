package client;

import datamodel.StreamData;
import datamodel.enums.EnumGender;
import datamodel.enums.EnumMessageType;
import datamodel.json.JSONClientExit;
import datamodel.json.JSONClientUserData;
import datamodel.json.chat.JSONMessageFromClient;
import datamodel.json.chat.JSONMessageFromServer;
import datamodel.json.JSONUsersList;
import datamodel.json.audio.JSONAudioClientUserData;
import datamodel.json.audio.JSONAudioRequestFromClient;
import datamodel.json.audio.JSONAudioRequestFromServer;
import datamodel.json.audio.JSONAudioResponseFromClient;
import datamodel.json.audio.JSONAudioResponseFromServer;
import datamodel.json.stream.JSONStreamClientUserData;
import datamodel.json.stream.JSONStreamRequestFromClient;
import datamodel.json.stream.JSONStreamRequestFromServer;
import datamodel.json.stream.JSONStreamResponseFromClient;
import datamodel.json.stream.JSONStreamResponseFromServer;
import datamodel.pojo.Message;
import datamodel.pojo.SoundPacket;
import datamodel.pojo.User;
import datamodel.pojo.Utils;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import server.ChatServer;

/**
 *
 * @author Lazar Davidovic
 */
//public class ClientThread implements Runnable {
public class ClientThread extends Thread {

    //Global
    InetAddress clientIPAdress;
    StreamData data;
    boolean endConnection;

    //TCP
    Socket serverSocketForTCP = null;

    ObjectInputStream serverInputStreamForTCP = null;
    ObjectOutputStream serverOutputStreamForTCP = null;

    //UDP
    int clientPortUDP;
    //int serverPortUDP;

    DatagramSocket serverSocketForUDP = null;

    //Support
    byte[] dataForClient;

    //Data
    private User user;

    boolean successConnection = false;

    private boolean streamingConfirmation = false;
    private boolean audioConfirmation = false;

    public ClientThread(Socket serverSocketForTCP) {
        this.serverSocketForTCP = serverSocketForTCP;
    }

    public void run() {
        //Gson gson = new GsonBuilder().create();

        try {
            serverInputStreamForTCP = new ObjectInputStream(serverSocketForTCP.getInputStream());
            serverOutputStreamForTCP = new ObjectOutputStream(serverSocketForTCP.getOutputStream());

            while (!endConnection) {
                if ((data = (StreamData) serverInputStreamForTCP.readObject()) != null) {
                    switch (data.getType()) {
                        case NEW_USER_DATA:
                            System.out.println("Popunjavanje podataka o korisniku pristiglih preko TCP-a.");
                            fillUserData(data);

                            successConnection = true;
                            System.out.println("Obavestavanje korisnika o uspesnosti konektovanja na server.");
                            successfulConnection(successConnection);

                            // Send all client by gender to client (UDP)
                            System.out.println("Slanje liste dostupnih korisnika novoprijavljenom korisniku...");
                            ChatServer.fireOnlineClientLists();

                            System.out.println("Izmnenjena lista korisnika je uspesno poslata svim korisnicima.");

                            break;
                        case TEXT_FROM_CLIENT:

//                        JSONMessageFromClient jsonUserMessage = new Gson().fromJson((String) data.getData(), JSONMessageFromClient.class);
                            JSONMessageFromClient jsonUserMessage = (JSONMessageFromClient) data.getData();
                            System.out.println("Slanje poruke pristigle od korisnika.");
                            if (sendMessageToOther(jsonUserMessage)) {
                                System.out.println("Obavestavanje korisnika o uspesnosti slanja njegove poruke.");
                                notifyFromClient();
                            }

                            System.out.println("Cuvanje poruke u fajlu.");
                            saveMessageToTextFile(jsonUserMessage);

                            break;

                        case VIDEO_STREAM_REQUEST_FROM_CLIENT:
                            ChatServer.requireStreamConnection((JSONStreamRequestFromClient) data.getData());
                            break;

                        case VIDEO_STREAM_RESPONSE_FROM_CLIENT:
                            streamingConfirmation = ((JSONStreamResponseFromClient) data.getData()).isConfirmation();
                            break;

                        case AUDIO_REQUEST_FROM_CLIENT:

                            JSONAudioRequestFromClient accepted = (JSONAudioRequestFromClient) data.getData();
                            ChatServer.requireAudioConnection(accepted);

                            try {
                                Thread.sleep(2000);
                                ChatServer.sendAudioUserConfirmResponseList(accepted.getUserData().getUser());
                            } catch (InterruptedException ex) {
                                Logger.getLogger(ClientThread.class.getName()).log(Level.SEVERE, null, ex);
                            }

                            break;

                        case AUDIO_RESPONSE_FROM_CLIENT:
                            audioConfirmation = ((JSONAudioResponseFromClient) data.getData()).isConfirmation();
                            System.out.println("Dobio response od klijenta " + audioConfirmation);
                            break;

                        case END:
                            endConnection = true;
                            System.out.println("Slanje obavestenja korisniku o prijemu zahteva za prekidom konekcije.");
                            notifyClientAboutEnd();
                            serverSocketForUDP.close();
                            serverSocketForTCP.close();
                            ChatServer.removeOfflineClient(this);
                            ChatServer.fireOnlineClientLists();

                            break;
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }


    private void fillUserData(StreamData data) {

        try {
//            JSONClientUserData jsonClientUserData = new Gson().fromJson((String) data.getData(), JSONClientUserData.class);
            JSONClientUserData jsonClientUserData = (JSONClientUserData) data.getData();

            user = jsonClientUserData.getUser();
            System.out.println("Korisnik " + user + " je povezan na server! " + user.getAddress().getAddress() + "/" + user.getAddress().getPort());
            clientIPAdress = jsonClientUserData.getIPAdress();
            System.out.println("IP adresa korisnika " + user + " je: " + clientIPAdress.toString());
            clientPortUDP = jsonClientUserData.getPortUDP();
            System.out.println("UDP port klijenta je: " + clientPortUDP);

        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

    public User getUser() {
        return user;
    }

    private void sendUsersListUDP(ClientThread clientFireDataChange, List<User> users) {

        DatagramPacket packetForUDP;
        try {
            serverSocketForUDP = new DatagramSocket();

            JSONUsersList usersList = new JSONUsersList(users, new Date());
//            StreamData streamUsersList = new StreamData(EnumMessageType.USERS_LIST, SOPackJSON.execute(usersList));
            StreamData streamUsersList = new StreamData(EnumMessageType.USERS_LIST, usersList);

            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteOutputStream);
            objectOutputStream.writeObject(streamUsersList);

            dataForClient = byteOutputStream.toByteArray();

            System.out.println("Kreiranje UDP paketa.");
            packetForUDP = createDatagramPacket(dataForClient, dataForClient.length, clientFireDataChange.clientIPAdress, clientFireDataChange.clientPortUDP);

            serverSocketForUDP.send(packetForUDP);
            System.out.println("Lista dostupnih korisnika je uspesno poslata klijentu putem UDP-a.");

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    private DatagramPacket createDatagramPacket(byte[] dataForClient, int length, InetAddress IPAdress, int clientPortUDP) {
        return new DatagramPacket(dataForClient, length, IPAdress, clientPortUDP);
    }

    private void successfulConnection(boolean successConnection) {
        try {
            StreamData successData = new StreamData(EnumMessageType.SUCCESS_CONNECT, successConnection);

//            serverOutputStreamForTCP.writeObject(SOPackJSON.execute(successData));
            serverOutputStreamForTCP.writeObject(successData);
            serverOutputStreamForTCP.flush();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public void createAndSendOnlineUsersListToClient() {

        List<User> usersMale = new ArrayList<>();
        List<User> usersFemale = new ArrayList<>();

        switch (user.getGender()) {
            case MALE:
                for (ClientThread client : ChatServer.getClients()) {

                    if (client != null && client != this && client.getUser().getGender() == EnumGender.MALE) {
                        //"*** Novi korisnik : " + user + " je usao u chat sobu! ***"
                        usersMale.add(client.getUser());
                    }
                }
                System.out.println("Slanje online liste korisnika muskog pola korisniku: " + user.getUserName());

                //UDP
                //sendUsersListUDP(this, usersMale);

                //TCP
                sendUsersListTCP(this, usersMale);
                break;
            case FEMALE:
                for (ClientThread client : ChatServer.getClients()) {

                    if (client != null && client != this && client.getUser().getGender() == EnumGender.FEMALE) {
                        //"*** Novi korisnik : " + user + " je usao u chat sobu! ***"
                        usersFemale.add(client.getUser());
                    }
                }
                System.out.println("Slanje online liste korisnika zenskog pola korisniku: " + user.getUserName());

                //UDP
                //sendUsersListUDP(this, usersFemale);

                //TCP
                sendUsersListTCP(this, usersFemale);
                break;

        }

    }

    private boolean sendMessageToOther(JSONMessageFromClient jsonUserMessage) {
        User from = jsonUserMessage.getFrom();
        List<User> to = jsonUserMessage.getTo();
        Message message = jsonUserMessage.getMessage();

        for (ClientThread clientToSend : ChatServer.getClients()) {
            for (User user : to) {
                if (clientToSend != null && (clientToSend.getUser().getUserName().equals(user.getUserName()))) {
                    sendMessage(clientToSend, from, message);

                }
            }

        }

        return true;
    }

    private void sendMessage(ClientThread clientToSend, User from, Message message) {
        JSONMessageFromServer jsonMessageFromServer = new JSONMessageFromServer(from, message);
//        StreamData forwardMessage = new StreamData(EnumMessageType.TEXT_FROM_SERVER, SOPackJSON.execute(jsonMessageFromServer));
        StreamData forwardMessage = new StreamData(EnumMessageType.TEXT_FROM_SERVER, jsonMessageFromServer);

        try {
            clientToSend.serverOutputStreamForTCP.writeObject(forwardMessage);
            clientToSend.serverOutputStreamForTCP.flush();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void notifyFromClient() {
        try {
//            StreamData successData = new StreamData(EnumMessageType.SUCCESS_FORWARD, SOPackJSON.execute(true));
            StreamData successData = new StreamData(EnumMessageType.SUCCESS_FORWARD, true);

            serverOutputStreamForTCP.writeObject(successData);
            serverOutputStreamForTCP.flush();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void notifyClientAboutEnd() {
        JSONClientExit exitClient = new JSONClientExit(user, new Date());
//        StreamData streamExit = new StreamData(EnumMessageType.END, SOPackJSON.execute(exitClient));
        StreamData streamExit = new StreamData(EnumMessageType.END, exitClient);

        try {
            serverOutputStreamForTCP.writeObject(streamExit);
            serverOutputStreamForTCP.flush();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    private void saveMessageToTextFile(JSONMessageFromClient jsonUserMessage) {

        try {
//            ObjectInputStream savedMessages = new ObjectInputStream(new FileInputStream("messages.ser"));
//            List<JSONMessageFromClient> messages = (List<JSONMessageFromClient>) savedMessages.readObject();

            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("messages.ser", true));
            oos.writeObject(jsonUserMessage);

        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();

        }

//        catch (ClassNotFoundException ex) {
//            ex.printStackTrace();
//        }
    }

    private void sendUsersListTCP(ClientThread clientFireDataChange, List<User> users) {

        JSONUsersList usersList = new JSONUsersList(users, new Date());
//            StreamData streamUsersList = new StreamData(EnumMessageType.USERS_LIST, SOPackJSON.execute(usersList));
        StreamData streamUsersList = new StreamData(EnumMessageType.USERS_LIST, usersList);

        try {
            clientFireDataChange.serverOutputStreamForTCP.writeObject(streamUsersList);
            clientFireDataChange.serverOutputStreamForTCP.flush();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        System.out.println("Lista dostupnih korisnika je uspesno poslata klijentu putem TCP-a.");

    }

    public void sendAudioRequest(JSONAudioRequestFromClient audioRequest) {
        JSONAudioRequestFromServer audioRequestFromServer = new JSONAudioRequestFromServer(audioRequest);
        StreamData requestData = new StreamData(EnumMessageType.AUDIO_REQUEST_FROM_SERVER, audioRequestFromServer);

        try {
            serverOutputStreamForTCP.writeObject(requestData);
            serverOutputStreamForTCP.flush();
        } catch (IOException ex) {
            ex.printStackTrace();
        }  
    }
    
    public void sendAudioResponse(List<JSONAudioClientUserData> confirmedUsers) {
        JSONAudioResponseFromServer responseFromServer = new JSONAudioResponseFromServer(confirmedUsers);
        StreamData requestData = new StreamData(EnumMessageType.AUDIO_RESPONSE_FROM_SERVER, responseFromServer);

        try {
            serverOutputStreamForTCP.writeObject(requestData);
            serverOutputStreamForTCP.flush();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    public void sendStreamRequest(JSONStreamRequestFromClient jsonStreamRequestFromClient) {
        JSONStreamRequestFromServer streamRequestFromServer = new JSONStreamRequestFromServer(jsonStreamRequestFromClient);
        StreamData requestData = new StreamData(EnumMessageType.VIDEO_STREAM_REQUEST_FROM_SERVER, streamRequestFromServer);

        try {
            serverOutputStreamForTCP.writeObject(requestData);
            serverOutputStreamForTCP.flush();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public boolean isStreamingConfirmation() {
        return streamingConfirmation;
    }

    public boolean isAudioConfirmation() {
        return audioConfirmation;
    }
    public void sendStreamResponse(List<JSONStreamClientUserData> confirmedUsers) {
        JSONStreamResponseFromServer responseFromServer = new JSONStreamResponseFromServer(confirmedUsers);
        StreamData requestData = new StreamData(EnumMessageType.VIDEO_STREAM_RESPONSE_FROM_SERVER, responseFromServer);

        try {
            serverOutputStreamForTCP.writeObject(requestData);
            serverOutputStreamForTCP.flush();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }
    
   
}
