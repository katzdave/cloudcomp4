package masterprotocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Vector;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import masterserver.*;

public class MasterProtocol {
  
  //-1 spot reserved for leader
  final ConcurrentMap<Integer, Socket> sockets;
  //contains the IP address and port number of the consumer
  //stores it as ipAddress~portNumberOfServerSocket
  final ConcurrentMap<Integer, String> consumerConnectionData;
  final BlockingQueue<ConsumerInfo> notBusyConsumers;
  final BlockingQueue<Message> incomingMessages;
  final BlockingQueue<Message> outgoingMessages;
  
  final ConcurrentMap<Integer, String> backupsConnectionData;
  String backupString = "b";
  final List<String> serverList;
  Set<Integer> notifyList;
  int serverPort;
  Boolean isLeader;
  Boolean isrunning;
  Boolean connected;
  String currentLeaderInfo;
  
  String connectionData;
  String incomingString;
  Message incomingMessage;
  String [] messagePieces;
  String [] acceptorMessagePieces;
  String [] disMessagePieces;
  String [] slistMessagePieces;

  public MasterProtocol(int serverPort,
                        String leadIP, 
                        int leadPort,
                        Boolean isrunning,
                        ConcurrentMap<Integer, Socket> sockets,
                        BlockingQueue<Message> incomingMessages,
                        BlockingQueue<Message> outgoingMessages) 
          throws IOException, InterruptedException {
    this.sockets = sockets;
    this.incomingMessages = incomingMessages;
    this.outgoingMessages = outgoingMessages;
    this.consumerConnectionData = new ConcurrentHashMap<>();
    this.notBusyConsumers = new PriorityBlockingQueue<>();
    
    this.backupsConnectionData = new ConcurrentHashMap<>();
    serverList = new Vector<> ();
    this.serverPort = serverPort;
    this.isrunning = isrunning;
    connected = false;
    if ((leadIP.equals("127.0.0.1") || 
         leadIP.equals("localhost") || 
         leadIP.equals("::1"))
            && serverPort == leadPort) {
      isLeader = true;
    } else {
      isLeader = false;
      Socket leaderSocket = new Socket(leadIP, leadPort);
      currentLeaderInfo = (leadIP + "~" + leadPort);
      sockets.put(-1, leaderSocket);
      BufferedReader masterStream = new BufferedReader(
        new InputStreamReader(leaderSocket.getInputStream()));
      Connection connection = new Connection(-1,
                                        isrunning,
                                        incomingMessages,
                                        masterStream,
                                        this);
      connection.start();
      outgoingMessages.put(new Message(-1, "s " + serverPort));
    }
  }
  
  //the letters {a, r, c, k} are already used
  /*
   * character 'a': add
   * Consumers will need to send a "not busy status": 
        1) upon initialization
        2) every time it is paired with a client and still not busy
   *   
   * character 'r': request
   * Producers will request connections
   */
  // all handled in one thread
  public void processManagerMessages() throws InterruptedException {
    incomingMessage = incomingMessages.take();
    messagePieces = incomingMessage.message.split(" ");
    System.out.println("Processing message from " 
            + incomingMessage.connectedID + ": " + incomingMessage.message);
    switch(messagePieces[0].charAt(0)) {
      case 'a':
        System.out.println("Received add ready consumer request");
        addReadyConsumer(incomingMessage.connectedID, Double.parseDouble(messagePieces[1]));
        break;
      case 'r':
        System.out.println("Received producer request to be paired");
        handlePairClientWithConsumer(incomingMessage.connectedID, messagePieces[1]);
        break;
      case 'b':
        connected = true;
        backupString = incomingMessage.message;
        System.out.println("Received updated backupString: " + backupString);
        break;
      case 'l':
        if (sockets.containsKey(-1)) {
          try {
            sockets.get(-1).close();
          } catch (IOException e) {}
        }
        messagePieces = messagePieces[1].split("~");
        connectToLeader(messagePieces[0], messagePieces[1]);
        break;
      default:
        System.err.println("Received invalid message from clientID: "
                + incomingMessage.connectedID);
    }
  }
  
  boolean isMyIpPort(String ip, String port) throws UnknownHostException {
    if ((ip.equals(Inet4Address.getLocalHost().getHostAddress())
               || ip.equals(InetAddress.getLocalHost().getHostAddress())
               || ip.equals("127.0.0.1"))
               && port.equals(""+serverPort))
      return true;
    return false;
  }
  //handles pairing request from client
  void handlePairClientWithConsumer (int connectedID, String filesz)
          throws InterruptedException  {
    if (notBusyConsumers.isEmpty()) {
      //send busy message
      outgoingMessages.put(new Message(connectedID, "n"));
    } else {
      //send client ip and port of client to connect to
      String message = "y";
      int maxConsumers = getNumConsumers(filesz);
      for (int i = 0; i != maxConsumers; ++i) {
        if (!notBusyConsumers.isEmpty()) {
          message += (" " + consumerConnectionData.get(notBusyConsumers.remove().id));
        } else {
          break;
        }
      }
      outgoingMessages.put(new Message(connectedID, message));
    }
  }

  int getNumConsumers (String filesz) {
    long size = Long.parseLong(filesz);
    if ((size /= 10000) == 0)
      return 1;
    int numConsumers = 1;
    while ((size /= 10) != 0) {
      numConsumers*=2;
      if (numConsumers >= notBusyConsumers.size())
        return notBusyConsumers.size();
    }
    return numConsumers;
  }

  void addReadyConsumer(int connectedID, Double load) {
    notBusyConsumers.add(new ConsumerInfo(connectedID, load));
  }
  
  //handled across multiple threads, utilizing Connection class
  public void handleDisconnection(int connectedID) {
    sockets.remove(connectedID);
    if (!connected && connectedID == -1)
      return;
    if (connectedID == -1) {
      disMessagePieces= backupString.split(" ", 3);

      //wait until new backupString is updated
      while (disMessagePieces.length < 2) {
        disMessagePieces= backupString.split(" ", 3);
      }
      currentLeaderInfo = disMessagePieces[1];
      String[] nextLeader = currentLeaderInfo.split("~");
      if (disMessagePieces.length == 3)
        backupString = "b " + disMessagePieces[2];
      else if (disMessagePieces.length == 2)
        backupString = "b";
      boolean resolved = false;
      while (!resolved) {
        try {
          if (isMyIpPort(nextLeader[0], nextLeader[1])) {
            System.out.println("I'm the leader!");
            isLeader = true;
            updateServerList();
            resolved = true;
          } else {
            connectToLeader(nextLeader[0], nextLeader[1]);
            resolved = true;
          }
          //System.out.println("new backupString: " + backupString);
        } catch (UnknownHostException e) {
          System.err.println("Resolution bug!");
        }
      }
    } else if (consumerConnectionData.containsKey(connectedID)) {
      consumerConnectionData.remove(connectedID);
      if (notBusyConsumers.contains(connectedID)) {
        notBusyConsumers.remove(connectedID);
        System.out.println("removed ID " + connectedID + " from Queue");
      }
    } else if (backupsConnectionData.containsKey(connectedID) && isLeader) {
      System.out.println("A backup disconnected");
      serverList.remove(backupsConnectionData.get(connectedID));
      sockets.remove(connectedID);
      updateBackupString();
      sendToAllUpdate();
    } else {
      System.err.println("A producer disconnected");
    }
  }
  
  //first process identifying string, then send out backup list
  //handled in one thread: ConnectionAcceptor class
  public boolean processAcceptorMessages(int numConnections, 
                                         BufferedReader incomingStream, 
                                         Socket cSocket) 
          throws IOException {
    incomingString = incomingStream.readLine();
    if (incomingString == null) {
      System.out.println("Could not get identifying message!");
      return false;
    }
    //System.out.println("Identifying Message: " + incomingString);
    acceptorMessagePieces = incomingString.split(" ");
    if (!isLeader) {
      if (acceptorMessagePieces[0].equals("s")) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
      }
      if (!isLeader) {
        //System.err.println("I'm not a leader! Sending leader info");
        try {
          outgoingMessages.put(new Message(numConnections, 
                 "l " + currentLeaderInfo));
        } catch (InterruptedException ex) {}
        return false;
      }
    }
    System.out.println("~!!!!!: " + incomingString);
    switch(acceptorMessagePieces[0].charAt(0)) {
      case 'c':
        try {
          outgoingMessages.put(new Message(numConnections, backupString));
          incomingMessages.put(new Message(numConnections, "r " + acceptorMessagePieces[1]));
        } catch (InterruptedException ex) {
          System.err.println("interrupted adding message to queue");
        }
        break;
      case 'k': 
        connectionData = (cSocket.getInetAddress().getHostAddress().toString() 
                + "~" + acceptorMessagePieces[1]);
        consumerConnectionData.put(numConnections, connectionData);
        try {
          outgoingMessages.put(new Message(numConnections, backupString));
          incomingMessages.put(new Message(numConnections, "a " + acceptorMessagePieces[2]));
        } catch (InterruptedException ex) {
          System.err.println("interrupted adding message to queue");
        }
        break;
      case 's':
        connectionData = (cSocket.getInetAddress().getHostAddress().toString() 
                + "~" + acceptorMessagePieces[1]);
        acceptorMessagePieces = backupString.split(" ");
        backupsConnectionData.put(numConnections, connectionData);
        if (!serverList.contains(connectionData)) {
          backupString += (" " +connectionData);
          serverList.add(connectionData);
        }
        sendToAllUpdate();
        break;
      default:
        System.err.println("Received invalid message!: " + acceptorMessagePieces[0]);
        return false;
    }
    return true;
  }
  
  //may be called across multiple Connection threads
  synchronized void updateBackupString() {
    backupString = "b";
    for (int i = 0; i != serverList.size(); ++i) {
      backupString += (" " + serverList.get(i));
    }
  }
  
  //may be called across multiple Connection threads
  synchronized void sendToAllUpdate() {
    notifyList = backupsConnectionData.keySet();
    sendToNotifyList();
    notifyList = consumerConnectionData.keySet();
    sendToNotifyList();
  }
  
  void sendToNotifyList() {
    for (Integer i : notifyList) {
      try {
        outgoingMessages.put(new Message(i, backupString));
      } catch (InterruptedException ex) {
        System.err.println("Didn't send message!");
      }
    }
  }
  
  void updateServerList() {
    slistMessagePieces = backupString.split(" ");
    serverList.clear();
    for (int i = 1; i != slistMessagePieces.length; ++i) {
      serverList.add(slistMessagePieces[i]);
    }
  }

  void connectToLeader(String leaderIP, String leaderPort) {
    System.out.println("Attempting to connect to leader: " 
            + leaderIP + " " + leaderPort);
    Socket leaderSocket;
    try {
      leaderSocket = new Socket(leaderIP, Integer.parseInt(leaderPort));
      currentLeaderInfo = (leaderIP + "~" + leaderPort);
      sockets.put(-1, leaderSocket);
      BufferedReader masterStream = new BufferedReader(
              new InputStreamReader(leaderSocket.getInputStream()));
      Connection connection= new Connection(-1,
                                            isrunning,
                                            incomingMessages,
                                            masterStream,
                                            this);
      connection.start();
      try {
        outgoingMessages.put(new Message(-1, "s " + serverPort));
      } catch (InterruptedException e) {

      }
    } catch (IOException ex) {
      System.err.println("Couldn't connect to master!");
      System.exit(1);
    }
  }
}