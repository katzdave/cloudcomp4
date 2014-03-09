/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package imageconsumer;

import masterserver.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.Socket;
import java.net.ServerSocket;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.List;

import java.awt.image.BufferedImage;


/**
 *
 * @author Eli
 */
public class Producer extends Thread {
  public static final String DELIM = " ";
	public static final int NUM_THREADS = 4;
  static AtomicInteger availCores;
  static AtomicInteger uniqueID;
  
  private ExecutorService executorPool;
  private ServerSocket sSocket;
  
  boolean isrunning;
  
  private MasterWrapper masterSocket;
  
  private ConcurrentHashMap<Integer,List<BufferedImage>> clientMap;

  public Producer(int myPort, MasterWrapper masterSocket, boolean isrunning) {
    
    //Number of available CubbyConsumers
    availCores = new AtomicInteger(NUM_THREADS);
    uniqueID = new AtomicInteger(0);
    
    sSocket = null;
    this.masterSocket = masterSocket;
    try {
      sSocket = new ServerSocket(myPort);
    } catch (IOException e) {
      System.err.println("Problem creating serverSocket ");
      System.exit(0);
    }
    clientMap = new ConcurrentHashMap<Integer,List<BufferedImage>>();
    executorPool = Executors.newCachedThreadPool();
	  
    this.isrunning = isrunning;
    for (int i=0; i<NUM_THREADS; i++) {
      masterSocket.sendMessage("a"+DELIM+"3.2");
    }
  }
  
  @Override
  public void run() {
    while (isrunning) {
      try {
        Socket client = sSocket.accept();
        System.out.println("Producer connected to client: " + client);
        availCores.getAndDecrement();
        
        if(availCores.get() > 0) {
          //tell Master we're not too busy.
          masterSocket.sendMessage("a"+DELIM+"3.2");//SIGAR
        }
        
        executorPool.execute(new Consumer(client, 
                                          masterSocket, 
                                          clientMap,
                                          executorPool));
        
      } catch (IOException ioe) {
        System.err.println("Producer.start(): Problem accepting client");
      }
    }
  } 
  
  public static int getUniqueID() {
    return uniqueID.getAndIncrement();
  }
}
