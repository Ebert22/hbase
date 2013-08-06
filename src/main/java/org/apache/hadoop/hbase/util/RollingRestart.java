package org.apache.hadoop.hbase.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.ipc.HRegionInterface;
import org.apache.hadoop.hbase.master.AssignmentPlan;
import org.apache.hadoop.hbase.master.RegionPlacement;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class RollingRestart {

  private static final Log LOG = LogFactory.getLog(RollingRestart.class);

  HServerAddress serverAddr;
  final Configuration conf;
  AssignmentPlan plan;
  HRegionInfo[] regions;
  STAGE currentState;
  HBaseAdmin admin = null;
  int sleepIntervalAfterRestart = 0;
  int regionDrainInterval = 0;
  int regionUndrainInterval = 0;
  int getOpFrequency = 0;
  int sleepIntervalBeforeRestart = 0;
  int moveTimeoutInterval = 60000;
  int moveRetries = 1;
  boolean useHadoopCtl = true;
  HashMap<HServerAddress, HRegionInterface> serverConnectionMap =
      new HashMap<HServerAddress, HRegionInterface>();
  ArrayList<RegionChecker> regionCheckers = new ArrayList<RegionChecker>();

  final static int DEFAULT_SLEEP_AFTER_RESTART_INTERVAL = 10000;
  final static int DEFAULT_SLEEP_BEFORE_RESTART_INTERVAL = 10000;
  final static int DEFAULT_REGION_DRAIN_INTERVAL = 1000;
  final static int DEFAULT_REGION_UNDRAIN_INTERVAL = 10000;
  final static int DEFAULT_GETOP_FREQUENCY = 1000;
  final static int DEFAULT_MOVE_RETRIES = 1;
  final static int DEFAULT_MOVE_TIMEOUT = 60000;

  RollingRestart(String serverName, int regionDrainInterval,
      int regionUndrainInterval, int sleepIntervalAfterRestart,
      int sleepIntervalBeforeRestart, int getOpFrequency,
      boolean useHadoopCtl) throws IOException {

    this.sleepIntervalAfterRestart = sleepIntervalAfterRestart;
    this.sleepIntervalBeforeRestart = sleepIntervalBeforeRestart;
    this.useHadoopCtl = useHadoopCtl;
    this.regionDrainInterval = regionDrainInterval;
    this.regionUndrainInterval = regionUndrainInterval;
    this.getOpFrequency = getOpFrequency;

    conf = HBaseConfiguration.create();
    this.moveRetries = conf.getInt("hbase.rollingrestart.move.maxretries", DEFAULT_MOVE_RETRIES);
    this.moveTimeoutInterval = conf.getInt("hbase.rollingrestart.move.timeout", DEFAULT_MOVE_TIMEOUT);

    try {
      admin = new HBaseAdmin(conf);
    } catch (MasterNotRunningException e) {
      currentState = STAGE.FAIL;
      return;
    }
    this.serverAddr = new HServerAddress(serverName, 60020);

    currentState = STAGE.SETUP;
  }

  HRegionInterface getHRegionConnection(HServerAddress server) throws IOException {
    if (serverConnectionMap.get(server) == null) {
      HRegionInterface rs = admin.getConnection().getHRegionConnection(server);
      serverConnectionMap.put(server, rs);
      return rs;
    }
    return serverConnectionMap.get(server);
  }

  enum STAGE {
    SETUP,
    DRAIN,
    RESTART_REGIONSERVER,
    UNDRAIN,
    COMPLETE,
    FAIL
  };

  boolean moveRegion(final HRegionInfo region) throws Exception {
    HRegionInterface destinationServer = getDestinationServer(region);

    if (destinationServer == null) {
      LOG.debug("No preferred server found for " + region.getRegionNameAsString() +
          ". Skipping...");
      return false;
    }

    LOG.info("Moving region:" + region.getRegionNameAsString() + " to " +
        destinationServer.getHServerInfo().getHostname());

    int numTries = 0;
    long startTimeInMs = System.currentTimeMillis();

    admin.moveRegion(region.getRegionName(),
        destinationServer.getHServerInfo().getHostnamePort());

    while (true) {
      try {
        HRegionInfo r = destinationServer.getRegionInfo(region.getRegionName());
        if (r != null) {
          break;
        }
      } catch (Exception e) {
        if ((System.currentTimeMillis() - startTimeInMs) > moveTimeoutInterval) {
          if (++numTries >= this.moveRetries) {
            LOG.warn("Reached max " + numTries + " tries while moving region " +
                region.getRegionNameAsString() + " to destination server " +
                destinationServer.getHServerInfo().getHostname());
            return false;
          }

          LOG.warn("Timed out while moving region " +
              region.getRegionNameAsString() + " to destination server " +
              destinationServer.getHServerInfo().getHostname() + ". Retrying");

          admin.moveRegion(region.getRegionName(),
              destinationServer.getHServerInfo().getHostnamePort());
          startTimeInMs = System.currentTimeMillis();
        }
        LOG.info("Waiting for region to come online on destination region server");
      }
      Thread.sleep(2000);
    }
    return true;
  }

  /**
   * Restarts the regionserver using the hadoopctl script. This adds
   * a dependency on the hadoopctl script.
   * @throws IOException
   * @throws InterruptedException
   * @param drainAndStopOnly
   */
  void restart(boolean drainAndStopOnly) throws IOException, InterruptedException {
    System.out.println("Shutting down the region server after sleep of " +
        this.sleepIntervalBeforeRestart);
    Thread.sleep(this.sleepIntervalBeforeRestart);
    String cellName = conf.get("titan.cell.name");
    String sshCmd = "ssh hadoop@" + serverAddr.getHostname();

    try {
      if (this.useHadoopCtl) {
        String sshCmdToStopRS = sshCmd + " hadoopctl stop regionserver";
        LOG.info("Executing " + sshCmdToStopRS);
        Process stop = Runtime.getRuntime().exec(sshCmdToStopRS);

        stop.waitFor();

        LOG.info("Exit value for the region server stop " + stop.exitValue());

        if (stop.exitValue() != 0) {
          LOG.error("Failed to stop regionserver. Aborting..");
          throw new IOException("Failed to stop regionserver. Aborting..");
        }
        if(drainAndStopOnly) {
          LOG.info("Only told to stop the region server. Returning..");
          return;
        }

        String sshCmdToStartRS = sshCmd + " hadoopctl start regionserver";
        LOG.info("Executing " + sshCmdToStartRS);
        Process start = Runtime.getRuntime().exec(sshCmdToStartRS);

        start.waitFor();

        LOG.info("Exit value for the region server start " + start.exitValue());

        if (start.exitValue() != 0) {
          LOG.error("Failed to start regionserver. Aborting..");
          throw new IOException("Failed to start regionserver. Aborting..");
        }

      } else {
        String sshCmdToStopRS = sshCmd + " /usr/local/hadoop/" +
            cellName + "-HBASE/bin/hbase-daemon.sh stop regionserver";
        LOG.info("Executing " + sshCmd);
        Process p = Runtime.getRuntime().exec(sshCmdToStopRS);
        p.waitFor();

        LOG.info("Exit value for the region server stop " + p.exitValue());

        if (p.exitValue() != 0) {
          LOG.error("Failed to stop regionserver. Aborting..");
          throw new IOException("Failed to stop regionserver. Aborting..");
        }
        String sshCmdToStartRS = sshCmd + " /usr/local/hadoop/" +
            cellName + "-HBASE/bin/hbase-daemon.sh start regionserver ";
        p = Runtime.getRuntime().exec(sshCmdToStartRS);
        p.waitFor();

        LOG.info("Exit value for the region server start " + p.exitValue());

        if (p.exitValue() != 0) {
          LOG.error("Failed to start regionserver. Aborting..");
          throw new IOException("Failed to start regionserver. Aborting..");
        }
      }

    } catch (IOException e1) {
      System.out.println("Restart of regionserver failed");
      throw e1;
    }

    // Wait for it to come back online
    while(true) {
      try {
        if (getHRegionConnection(serverAddr).isStopped() == false) {
          break;
        }
     } catch (Exception e) {
       System.out.println("Waiting for region server to come online.");
       Thread.sleep(1000);
     }
    }
    Thread.sleep(this.sleepIntervalAfterRestart);
  }

  final HRegionInterface getDestinationServer(final HRegionInfo region) throws IOException {

    // We are undraining, return the same regionserver back
    if (currentState == STAGE.UNDRAIN) {
      return getHRegionConnection(serverAddr);
    }

    List<HServerAddress> serversForRegion = plan.getAssignment(region);

    if (serversForRegion == null) {
      return null;
    }
    // Get the preferred region server from the Assignment Plan
    for (HServerAddress server : serversForRegion) {
      if (!server.equals(serverAddr)) {
        try {
          HRegionInterface candidate = getHRegionConnection(server);
          if (!candidate.isStopped()) {
            return candidate;
          }
        } catch (IOException e) {
          // server not online/reachable skip
        }
      }
    }

    // if none found we should return a random server. For now return null
    return null;
  }

  void drainServer() throws Exception {

    LOG.info("Draining region server");

    currentState = STAGE.DRAIN;
    for (HRegionInfo region : regions) {
      if (region.isMetaRegion() ||
          region.isRootRegion()  ||
          region.getRegionNameAsString().contains(",,")) {
        continue;
      }
      if (!moveRegion(region)) {
        throw new IOException("Failed to move region " + region.getRegionNameAsString() + ". Aborting");
      }
      Thread.sleep(this.regionDrainInterval);
    }
  }

  void undrainServer() throws Exception {
    LOG.info("Undraining region server");
    currentState = STAGE.UNDRAIN;
    for (HRegionInfo region : regions) {
      if (region.isMetaRegion() ||
          region.isRootRegion() ||
          region.getRegionNameAsString().contains(",,")) {
        continue;
      }
      if (!moveRegion(region)) {
        throw new IOException("Failed to move region " + region.getRegionNameAsString() + ". Aborting");
      }
      Thread.sleep(this.regionUndrainInterval);
    }
  }

  void setup() throws IOException {

    LOG.info("Setup started");
    // blacklist the server
    admin.getMaster().addServerToBlacklist(
        getHRegionConnection(serverAddr).getHServerInfo().getHostnamePort());

    regions = getHRegionConnection(serverAddr).getRegionsAssignment();

    RegionPlacement regionPlacementProxy = new RegionPlacement(conf);
    plan = regionPlacementProxy.getExistingAssignmentPlan();

    // Start the region checker for all the regions present on the region server
    for (HRegionInfo region : regions) {
      RegionChecker checker =
          new RegionChecker(region, region.getTableDesc().getNameAsString(), conf, this.getOpFrequency);
      this.regionCheckers.add(checker);
      checker.start();
    }

    LOG.info("Setup Complete");
  }

  public void clear(boolean drainAndRestartOnly) {

    for (RegionChecker r : this.regionCheckers) {
      r.stop();
      r.printInfo();
    }
    this.regionCheckers.clear();

    if (drainAndRestartOnly) {
      LOG.warn("Not removing the regionserver from the blacklist.");
      return;
    }

    try {
      admin.getMaster().clearBlacklistedServer(
          getHRegionConnection(serverAddr).getHServerInfo().getHostnamePort());
    } catch (IOException e) {
      LOG.error("Failed to remove the server from black list. Please remove it");
    }
   }

   public static void clearAll() {
     Configuration conf = HBaseConfiguration.create();

     try {
       HBaseAdmin admin = new HBaseAdmin(conf);
       try {
         admin.getMaster().clearAllBlacklistedServers();
       } catch (IOException e) {
         LOG.error("Failed to clear black listed regionservers.");
       }
     } catch (MasterNotRunningException e) {
       LOG.error("Cannot initialize admin. Error: " + e.getMessage());
     }
   }

   public class RegionChecker implements Runnable {
     final HRegionInfo regionInfo;
     final byte[] startKey, endKey;
     final String tableName;
     final Configuration conf;
     int frequency;
     Map<Long, Exception> errors = new HashMap<Long, Exception>();
     long lastTimeExceptionSeen = 0;
     long totalTimeout = 0;
     ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(1);
     final Random rand = new Random ();
     HTable table = null;

     RegionChecker(final HRegionInfo info, final String tableName,
           final Configuration conf, int frequency) {
       this.regionInfo = info;
       this.tableName = tableName;
       this.conf = conf;

       this.frequency = frequency;
       this.startKey = info.getStartKey();
       this.endKey = info.getEndKey();
       try {
         table = new HTable(conf, tableName);
       } catch (IOException e) {
         e.printStackTrace();
         return;
       }
     }

     public void start() {
      threadPool.scheduleAtFixedRate(this, frequency, frequency, TimeUnit.MILLISECONDS);
     }

     public void run() {
      long currentTime = 0;
      Get g = new Get(getOneRandomRow());
      try {
         currentTime = System.currentTimeMillis();

         table.get(g);

         if (lastTimeExceptionSeen != 0) {
           LOG.debug("Retry successful for region " + this.regionInfo.getRegionNameAsString());
           totalTimeout += (System.currentTimeMillis() - lastTimeExceptionSeen);
           lastTimeExceptionSeen = 0;
         }
       } catch (Exception e) {
         errors.put(currentTime, e);

         LOG.debug(regionInfo.getRegionNameAsString() +
             " encountered exception. Row: " + Bytes.toStringBinary(g.getRow()) + " Count = " + errors.size(), e);
         if (lastTimeExceptionSeen == 0) {
           lastTimeExceptionSeen = System.currentTimeMillis();
         }
       }
     }

     public void stop() {
       threadPool.shutdownNow();
       if (lastTimeExceptionSeen != 0) {
         totalTimeout += (System.currentTimeMillis() - lastTimeExceptionSeen);
       }
     }

     public byte[] getOneRandomRow () {

       byte[][] randomSplits = Bytes.split(startKey, endKey, true,
             rand.nextInt(16));
       return randomSplits[0];
     }

     public void printInfo() {
       LOG.info(regionInfo.getRegionNameAsString() +
           ": total timeout = " + totalTimeout + ", number of errors = " +  errors.size());
     }
   };

  /**
   * @param args
   * @throws ParseException
   */
  public static void main(String[] args) throws ParseException {

    Options options = new Options();

    options.addOption("s", "server", true,
        "Name of the region server to restart");
    options.addOption("r", "sleep_after_restart", true,
        "time interval after which the region server should be started assigning regions. Default : 10000ms");
    options.addOption("b", "sleep_before_restart", true,
        "time interval after which the region server should be restarted after draining. Default : 10000ms");
    options.addOption("d", "region_drain_interval", true,
        "time interval between region movements while draining. Default : 1000ms");
    options.addOption("u", "region_undrain_interval", true,
        "time interval between region movements while undraining. Default : 10000ms");
    options.addOption("g", "get_request_frequency", true,
        "frequency at which region checker will check for region availability. Default : 1000ms");
    options.addOption("c", "clear", false,
        "Clear all the regionserver from blacklist. Default : false");
    options.addOption("h", "dont_use_hadoopctl", false,
        "Don't use hadoopctl to restart the regionserver. Default : true");
    options.addOption("o", "drain_and_stop_only", false,
      "Drain and stop the region server(Works only with hadoopctl). Default : false");

    if (args.length == 0) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("RollingRestart", options, true);
      return;
    }

    CommandLineParser parser = new PosixParser();
    CommandLine cmd = parser.parse(options, args);

    String serverName = null;
    int sleepIntervalAfterRestart = RollingRestart.DEFAULT_SLEEP_AFTER_RESTART_INTERVAL;
    int regionDrainInterval = RollingRestart.DEFAULT_REGION_DRAIN_INTERVAL;
    int regionUndrainInterval = RollingRestart.DEFAULT_REGION_UNDRAIN_INTERVAL;
    int getOpFrequency = RollingRestart.DEFAULT_GETOP_FREQUENCY;
    int sleepIntervalBeforeRestart = RollingRestart.DEFAULT_SLEEP_BEFORE_RESTART_INTERVAL;
    boolean useHadoopCtl = true;
    boolean drainAndStopOnly = false;

    if (!cmd.hasOption("s")) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("RollingRestart", options, true);
      return;
    } else {
      serverName = cmd.getOptionValue("s");
    }

    if (cmd.hasOption("r")) {
      sleepIntervalAfterRestart = Integer.parseInt(cmd.getOptionValue("r"));
    }

    if (cmd.hasOption("b")) {
      sleepIntervalBeforeRestart = Integer.parseInt(cmd.getOptionValue("b"));
    }

    if (cmd.hasOption("h")) {
      useHadoopCtl = false;
    }

    if (cmd.hasOption("o")) {
      drainAndStopOnly = true;
    }

    if (cmd.hasOption("d")) {
      regionDrainInterval = Integer.parseInt(cmd.getOptionValue("d"));
    }

    if (cmd.hasOption("u")) {
      regionUndrainInterval = Integer.parseInt(cmd.getOptionValue("u"));
    }

    if (cmd.hasOption("g")) {
      getOpFrequency = Integer.parseInt(cmd.getOptionValue("g"));
    }

    RollingRestart rr = null;
    try {
      rr = new RollingRestart(serverName, regionDrainInterval,
          regionUndrainInterval, sleepIntervalAfterRestart,
          sleepIntervalBeforeRestart, getOpFrequency, useHadoopCtl);
    } catch (IOException e) {
      e.printStackTrace();
      LOG.error("Rolling restart failed for " + serverName);
      return;
    }

    Logger.getLogger("org.apache.zookeeper").setLevel(Level.ERROR);
    Logger.getLogger("org.apache.hadoop.hbase").setLevel(Level.INFO);

    if (cmd.hasOption("c")) {
      rr.clear(false);
      return;
    }

    try  {
      rr.setup();
      rr.drainServer();
      rr.restart(drainAndStopOnly);
      if (!drainAndStopOnly) {
        rr.undrainServer();
        LOG.info("Rolling restart complete for " + serverName);
      } else {
        LOG.info("Drain complete for " + serverName);
      }
      
    } catch (Exception e) {
      e.printStackTrace();
      LOG.error("Rolling restart failed for " + serverName + " at stage " + rr.currentState.name());
      switch (rr.currentState) {
        case SETUP:
          LOG.error("Cannot start rolling restart. Please retry");
          break;
        case DRAIN:
          LOG.error("Cannot drain regions from the server. It should " +
              "get reassigned by the Assignment Load Balancer. Need to " +
              "retry rolling restart.");
          break;
        case RESTART_REGIONSERVER:
          LOG.error("Unable to restart regionserver. Please restart it "
              + "manually.");
          break;
        case UNDRAIN:
          LOG.error("Unable to move the region back to the regionserver. " +
              " Assignment Load Balancer will rebalance the regions.");
         default:
       }
    } finally {
      rr.clear(drainAndStopOnly);
    }
  }
}
