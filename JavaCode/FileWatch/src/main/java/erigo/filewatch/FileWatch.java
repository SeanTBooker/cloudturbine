/*
Copyright 2016-2017 Erigo Technologies LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

/**
 * FileWatch
 * Watch a directory for new files; report throughput and latency metrics based on arrival time.
 * See notes below for Known Issues
 * <p>
 * @author John P. Wilson (JPW), Erigo Technologies
 * @version 04/17/2017
 * 
*/

package erigo.filewatch;

/*
 * Portions of this code which use the Java WatchService are based on the
 * WatchDir example program from Oracle:
 * 
 *     http://docs.oracle.com/javase/tutorial/essential/io/examples/WatchDir.java
 * 
 * The WatchDir sample program is more involved than FileWatch because it watches
 * a specified directory and recursively all sub-directories under the directory.
 * Even if a new directory is created, it will watch that directory.
 * 
 * Per the Oracle copyright, their copyright notice follows.
 * 
 * Copyright (c) 2008, 2010, Oracle and/or its affiliates. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   - Neither the name of Oracle nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 */

/*
 * FileWatch
 * 
 * Watch a directory for new files; report on timing.
 * 
 * This class is typically run in conjunction with FilePump.  FilePump writes
 * files to a local directory or puts them on a remote server via either FTP
 * or SFTP.  If they are written to a local directory, the files can be
 * transmitted using a third-party file sharing service.  FileWatch
 * observes files showing up at the destination folder.  FileWatch calculates
 * latency and throughput metrics.
 * 
 * To obtain accurate latency results, the clocks on systems at both ends of
 * the test (ie, the system where FilePump is running and the system where
 * FileWatch is running) should be synchronized.
 * 
 * FileWatch was specifically developed as a timing test utility for the
 * NASA SBIR CloudTurbine project, where data is streamed via third-party
 * file sharing services (such as Dropbox).  To test the third-party services,
 * a FilePump test program will put files into a given directory whose file
 * names are of the format:
 * 
 *    <epoch_time_in_millis>_<sequence_num>.txt
 * 
 * where <epoch_time_in_millis> is the time the file was created and
 * <sequence_num> is the sequence number of this file in the test.  The
 * sequence number sequentially increments over consecutive files.  This
 * index is a simple way (other than the file creation time also found
 * in the file name) to specify the file order.
 * 
 * In FileWatch, we record the time that the file appears in the watched folder
 * as well as the name of the file.  One of two methods are employed to detect
 * new files in the watched folder:
 * 
 * 1. Use Java WatchService; good tutorial on WatchService can be found at:
 * 
 *    https://docs.oracle.com/javase/tutorial/essential/io/notification.html
 *    
 *    We can register for different types of events with the WatchService.
 *    Registering for ENTRY_CREATE appears to be the cleanest option. We could
 *    also register for ENTRY_MODIFY events, but even if a file is simply
 *    copied into a directory, several ENTRY_MODIFY events will be triggered
 *    because the file's content and its parameters (such as timestamp) are
 *    independently set.
 * 
 * 2. Periodically poll the content of the watched directory.  This method is
 *    activated by specifying a poll interval using the "-p" command line
 *    flag.  This is a manual method, not event driven as is the case with
 *    WatchService.  We get list of files in the watched directory and scan
 *    through the list for new files.
 * 
 * After receiving a file named "end.txt", we perform post-processing on this
 * data and write the output file, which contains raw and processed data.  The
 * data written to file includes latency and throughput metrics and the
 * following:
 * 
 *     - Filename
 *     - Create time at source (msec)
 *     - Create time at source, normalized (sec)
 *     - Create time at sink (msec)
 *     - Create time at sink, normalized (sec)
 *     - Latency (sec)
 *     - Cumulative number of files at sink
 *     - Index from file
 *     - Out of order or missing?
 * 
 * The user may specify an optional "recaster" output directory.  When
 * specified, FileWatch in "recaster" mode can be used to conduct a round-trip
 * test as follows:
 *
 *           SYSTEM A                                        SYSTEM B
 *   --------------------------------------------------------------------------
 *
 *   FilePump  ==> folderA =====[FTP,Dropbox,etc]====> folderB ==> FileWatch/recaster
 *                                                                      ||
 *   FileWatch <== folderD <====[FTP,Dropbox,etc]===== folderC <========//
 *
 * With a test setup in this manner, FilePump and FileWatch on System A will
 * measure the throughput and latency of the entire round-trip between System A
 * and System B.  With this configuration, *NO CLOCK SYNCHRONIZATION* need be
 * conducted since the clock which is used for source time is the same as the clock
 * for destination time.
 *
 * Known Issues
 * ------------
 * 1. Recaster mode does not currently support FTP/SFTP (see FileWatch.java line 520).
 *
 */

import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;
import static java.nio.file.StandardWatchEventKinds.*;
import java.io.*;
import java.util.*;

import javax.swing.SwingUtilities;

import com.sun.nio.file.SensitivityWatchEventModifier;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.math3.stat.regression.SimpleRegression;

public class FileWatch {
    
    private final WatchService watcher = FileSystems.getDefault().newWatchService();
    
    // User can set this value using the "-p" command line flag
    // If value is > 0, we will use polling instead of the WatchService to check for new files
    private int pollInterval = 0;
    
    // Use a LinkedHashMap because it will maintain insertion order
    private final LinkedHashMap<Double,String> fileData = new LinkedHashMap<Double,String>();
    
    private int num_received_files = 0;
    private int num_skipped_files = 0;
    
    private static final String DEFAULT_WATCH_DIR = "."; 
    
    //
    // Variables used when running in "recaster" mode
    //
    private boolean bRecast = false;					// are we running in recaster mode?
    private boolean bOutputResultsInRecastMode = false;	// when we are in recaster mode, output results at the end of the test?
    Random random_generator = new Random();				// used to set random number as the content of the recast file
    int random_range = 999999;
    private File outputDir = null;						// where to put the new recaster output files
    
    // If this FileWatch is observing test data files which have come round-trip
    // (ie, gone from FilePump, to a recaster machine and then back to the
    // FilePump machine) then set this flag true so that the output latency
    // metric results are cut in half
    private boolean bAdjustLatencyMetrics = false;
    
    // Output plot titles
    private String latencyPlotTitle = "Latency";
    private String throughputPlotTitle = "Throughput";
    private String customPlotTitle = "";
    
    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>)event;
    }
    
    /**
     * Store creation time and sequence number parsed from filename
     */
    private class FilenameInfo {
    	public long sourceCreationTime = 0;
    	public int sequenceNumber = 0;
    	public String errStr = null;
    }
    
    /**
     * Creates a WatchService and registers the given directory
     */
    // FileWatch(Path watchDir, String outputFilename, Path outputDirI) throws IOException {
    FileWatch(String[] argsI) throws IOException {
    	
    	//
		// Parse command line arguments
		//
		// 1. Setup command line options
		//
		Options options = new Options();
		// Boolean options (only the flag, no argument)
		options.addOption("h", "help", false, "Print this message.");
		options.addOption("a", "adjust_latency", false, "Divide latency results by 2 because test data files are from a recaster (round-trip).");
		options.addOption("d", "disp_recaster", false, "Display results when operating in recaster mode.");
		// Command line options that include an argument
		Option nextOption = Option.builder("i").argName("watchdir").hasArg().desc("Directory to watch for incoming test data files (must be an existing directory); default = \"" + DEFAULT_WATCH_DIR + "\".").build();
		options.addOption(nextOption);
		nextOption = Option.builder("o").argName("outfilename").hasArg().desc("Name of the output metrics data file; must be a new file.").build();
		options.addOption(nextOption);
		nextOption = Option.builder("p").argName("pollinterval_msec").hasArg().desc("Watch for new files by polling (don't use Java WatchService); sleep for <pollinterval_msec> (milliseconds) between scans.").build();
		options.addOption(nextOption);
		nextOption = Option.builder("r").argName("recasterdir").hasArg().desc("Recast test data files to the specified output directory (must be an existing directory).").build();
		options.addOption(nextOption);
		nextOption = Option.builder("t").argName("plottitle").hasArg().desc("Custom title for throughput and latency plots.").build();
		options.addOption(nextOption);
		
		//
		// 2. Parse command line options
		//
	    CommandLineParser parser = new DefaultParser();
	    CommandLine line = null;
	    try {
	        line = parser.parse( options, argsI );
	    }
	    catch( ParseException exp ) {
	        // oops, something went wrong
	        System.err.println( "Command line argument parsing failed: " + exp.getMessage() );
	     	return;
	    }
	    
	    //
	    // 3. Retrieve the command line values
	    //
	    if (line.hasOption("h")) {
	    	// Display help message and quit
	    	HelpFormatter formatter = new HelpFormatter();
	    	formatter.setWidth(100);
	    	formatter.printHelp( "FileWatch", options );
	    	return;
	    }
	    bAdjustLatencyMetrics = line.hasOption("a");
	    bOutputResultsInRecastMode = line.hasOption("d");
	    // Watch directory
	    String watchDirName = line.getOptionValue("i",DEFAULT_WATCH_DIR);
	    Path watchDir = Paths.get(watchDirName);
        if (!watchDir.toFile().isDirectory()) {
        	System.err.println("\nThe given watch directory does not exist.");
        	System.exit(-1);
        }
        // Recaster directory
        Path outputDirPath = null;
        String outputDirName = line.getOptionValue("r");
        if (outputDirName != null) {
        	outputDirPath = Paths.get(outputDirName);
        	if (!outputDirPath.toFile().isDirectory()) {
        		System.err.println("\nThe given recaster output directory does not exist.");
        		System.exit(-1);
        	}
        	// Make sure watchDir and outputDir aren't the same
        	if (watchDir.toAbsolutePath().compareTo(outputDirPath.toAbsolutePath()) == 0) {
        		System.err.println("\nThe recaster output directory cannot be the same as the watch directory.");
        		System.exit(-1);
        	}
        	bRecast = true;
    		outputDir = outputDirPath.toFile();
        }
        // Output filename
        String outputFilename = line.getOptionValue("o");
        if ( (outputFilename == null) && (!bRecast || bOutputResultsInRecastMode) ) {
        	System.err.println("\nMust specify the name of the output data file.");
        	System.exit(-1);
        }
        if (outputFilename != null) {
        	File outputFile = new File(outputFilename);
            if (outputFile.isDirectory()) {
            	System.err.println("\nThe given output data file is the name of a directory; must specify an output filename.");
            	System.exit(-1);
            } else if (outputFile.isFile()) {
            	System.err.println("\nThe given output data file is the name of an existing file; must specify a new filename.");
            	System.exit(-1);
            }
        }
        // Use polling to check for new files?
        String pollIntervalStr = line.getOptionValue("p");
        if (pollIntervalStr != null) {
        	try {
        		pollInterval = Integer.parseInt(pollIntervalStr);
        		if ( (pollInterval <= 0) || (pollInterval > 1000) ) {
        			throw new NumberFormatException("Illegal value");
        		}
        	} catch (NumberFormatException nfe) {
        		System.err.println("\nPoll interval must be an integer in the range 0 < x <= 1000");
        		System.exit(-1);
        	}
        }
        // Title for the throughput and latency plots
        customPlotTitle = line.getOptionValue("t","");
        
        // Make sure "end.txt" doesn't already exist in the directory; this file is our signal
        // that we're done processing
        File endFile = new File(watchDir.toFile(),"end.txt");
        if (endFile.exists()) {
        	System.err.println("\nMust delete \"" + endFile + "\" before running test.");
        	System.exit(-1);
        }
        // If we are recasting, make sure "end.txt" doesn't exist in the output directory either
        if (outputDirPath != null) {
        	endFile = new File(outputDirPath.toFile(),"end.txt");
            if (endFile.exists()) {
            	System.err.println("\nMust delete \"" + endFile + "\" in output directory before running test.");
            	System.exit(-1);
            }
        }
        
        if (pollInterval > 0) {
        	System.err.println("\nWatching directory \"" + watchDir + "\" for incoming files; using polling method");
        	processEvents_polling(watchDir.toFile());
        } else {
            // Register the directory with the WatchService
            // Only collect data for ENTRY_CREATE events.  Even if a file is just
        	// copied into a directory, several ENTRY_MODIFY events can be
        	// triggered because the file's content and its parameters (such as
        	// timestamp) are independently set.
            watchDir.register(watcher, new WatchEvent.Kind[]{StandardWatchEventKinds.ENTRY_CREATE}, SensitivityWatchEventModifier.HIGH);
        	System.err.println("\nWatching directory \"" + watchDir + "\" for incoming files; using WatchService events");
        	processEvents_watchservice();
        	watcher.close();
        }
        
        if ( !bRecast || bOutputResultsInRecastMode ) {
        	System.err.println("\nWrite data to file \"" + outputFilename + "\"...");
        }
        processData(outputFilename);
        
        System.err.println("\nFileWatch received a total of " + num_received_files + " files (not including \"end.txt\")");
        System.err.println("In processing, " + num_skipped_files + " files were skipped (due to wrong name format)");
        int num_files_processed = num_received_files - num_skipped_files;
        System.err.println("Thus, a total of " + num_files_processed + " files with properly formatted names were processed");
        
        System.err.println("\nTest is complete.");
        
    }
    
    /**
     * Detect new files by repeatedly polling the input folder; stop when we see a file called "end.txt".
     *
     */
    void processEvents_polling(File watchDir) throws IOException {
    	File[] files=null;
    	HashSet<String> hset = new HashSet<String>();
    	Boolean bDone = false;
        for (;;) {
        	// base time for all files currently in the queue
            double eventTime = (double)(System.currentTimeMillis());
        	files = watchDir.listFiles();
			for(File f:files) {
				// The HashSet (hset) is used as a file filter, indicating if we have already added this file.
				// We use that rather than LinkedHashMap's ".containsValue()" method because it is much quicker.
				String filenameStr = f.getName();
				if(hset.add(filenameStr)) {
					// This is a new file!
					bDone = processNewFile(filenameStr, eventTime);
	                if (bDone) {
	                	break;
	                }
				}
			}
			if (bDone) {
	        	// Directory must have gone away or we have received the "end.txt" file
	        	// we're done
	        	break;
	        }
			try { Thread.sleep(pollInterval); } catch(Exception e){};
        }
        
    }
    
    /**
     * Process new file events from the Java WatchService; stop when we see a file called "end.txt".
     *
     * This method uses Java WatchService, i.e. this is an event-driven method to get file updates.
     */
    void processEvents_watchservice() throws IOException {
        for (;;) {
            
            // wait for key to be signaled
            WatchKey key;
            try {
            	// The ".take()" method is a blocking read; can also use one of
            	// the ".poll()" non-blocking methods if desired.
                key = watcher.take();
            } catch (InterruptedException x) {
            	System.err.println("processEvents(): got InterruptedException: " + x);
                return;
            }
            
            // process all events
            // base time for all files currently in the queue
            double eventTime = (double)(System.currentTimeMillis());
            Boolean bDone = false;
            for (WatchEvent<?> event: key.pollEvents()) {
                Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                	// Note: even though we've only registered for ENTRY_CREATE
                	//       events, we get OVERFLOW events automatically.
                	System.err.println("OVERFLOW detected");
                    continue;
                }
                // Extract the filename from the event
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                String filenameStr = name.toString();
                bDone = processNewFile(filenameStr, eventTime);
                if (bDone) {
                	break;
                }
            }
            
            boolean valid = key.reset();
            if (!valid || bDone) {
            	// Directory must have gone away or we have received the "end.txt" file
            	// we're done
                break;
            }
            
        }
    }
    
    /**
     * Process a new file.  If we receive a file called "end.txt", that is
     * a signal to stop the test; return true in this case.
     */
    boolean processNewFile(String filenameStr, double eventTime) throws IOException {
    	boolean bDone = false;
    	if (filenameStr.equals("end.txt")) {
        	// this is the signal that we're done
        	bDone = true;
        	// If we are doing recasting, put "end.txt" in output directory also
        	if (bRecast) {
        		// create end.txt in the output directory
            	File endFile = new File(outputDir,filenameStr);
            	new FileOutputStream(endFile).close();
        	}
        } else {
        	System.err.print(".");
        	
        	// Make sure we are using a unique key
        	String testValue = fileData.get(new Double(eventTime));
        	if (testValue != null) {
        		// This key is already in use; create a unique key by adding a small artificial time increment
        		while (true) {
        			// Add a microsecond onto the event time
                	eventTime = eventTime + 0.001;
                	testValue = fileData.get(new Double(eventTime));
                	if (testValue == null) {
                		// We now have a unique key!
                		break;
                	}
        		}
        	}
            // System.out.format("%d  %s %s\n", eventTime, event.kind().name(), name);
            fileData.put(new Double(eventTime), filenameStr);
            ++num_received_files;
            if (num_received_files % 20 == 0) {
            	System.err.println(" " + String.format("%4d",num_received_files));
            }
            
            // When doing recasting, put new file in the output directory;
            // it must be different from the received file in order to avoid
            // deduplication.
            if (bRecast) {
            	// Do a quick check to make sure this is a valid filename before passing it on
            	boolean bFilenameErr = false;
            	// 1. name must start with a number
            	char firstChar = filenameStr.charAt(0);
            	bFilenameErr = !(firstChar >= '0' && firstChar <= '9');
            	// 2. name must end with ".txt"
            	if ( (!bFilenameErr) && (!filenameStr.endsWith(".txt")) ) {
            		bFilenameErr = true;
            	}
            	if (!bFilenameErr) {
            		//
            		// Write a random number in the new outgoing file
            		//
            		//   *******************************************
            		//   **                                       **
            		//   **    NEED TO ADD FTP/SFTP CAPABILITY    **
            		//   **                                       **
            		//   *******************************************
            		//
            		int random_num = (int)((double)random_range * random_generator.nextDouble());
            		File newFullFile = new File(outputDir,filenameStr);
            		try {
            			FileWriter fw = new FileWriter(newFullFile,false);
            			PrintWriter pw = new PrintWriter(fw);
            			// Write out random number to the file
            			pw.format("%06d\n",random_num);
            			pw.close();
            		} catch (Exception e) {
            			System.err.println("Error writing to output file " + filenameStr);
            		}
            	}
            }
        }
    	return bDone;
    }
    
    /**
     * Write data we've collected in the HashMap to the output file
     */
    void processData(String filenameI) {
    	
    	//
    	// Firewall
    	//
    	if (fileData.isEmpty()) {
    		System.err.println("No data to write out");
    		return;
    	}
    	
    	//
    	// Pre-process the data to calculate all needed metrics
    	//
    	List<Double> latencyList = new ArrayList<Double>();
    	List<String> filenameList = new ArrayList<String>();
    	List<Long> sinkCreationTimeList = new ArrayList<Long>();
    	List<Double> normalizedSinkCreationTimeList = new ArrayList<Double>();
    	List<Long> sourceCreationTimeList = new ArrayList<Long>();
    	List<Double> normalizedSourceCreationTimeList = new ArrayList<Double>();
    	List<Integer> cumulativeNumFilesList = new ArrayList<Integer>();
    	List<Integer> sequenceNumberList = new ArrayList<Integer>();
    	List<Boolean> outOfOrderList = new ArrayList<Boolean>();
    	int cumulativeNumberOfFiles = 0;
    	long earliestSourceCreationTime = Long.MAX_VALUE;
    	double latency_sum = 0.0;
    	double latency_min = Double.MAX_VALUE;
    	double latency_max = -Double.MAX_VALUE;
    	int previousIndex = 0;
    	for (Map.Entry<Double, String> entry : fileData.entrySet()) {
    		
    		// Strip the decimal part of the time off - it was only added in processEvents() to make unique hash keys
    		long sinkCreationTime = (long)Math.floor(entry.getKey());
    		
    		// Parse the filename
	        String filename = entry.getValue();
	        FilenameInfo fiObj = checkFilename(filename);
	        if (fiObj.errStr != null) {
	        	System.err.println(fiObj.errStr);
	        	++num_skipped_files;
	        	continue;
	        }
	        long sourceCreationTime = fiObj.sourceCreationTime;
	        int sequenceNumber = fiObj.sequenceNumber;
	        
	        // Save data in Lists
	        earliestSourceCreationTime = Math.min(earliestSourceCreationTime,sourceCreationTime);
	        double latency = (sinkCreationTime - sourceCreationTime)/1000.0;
	        latency_sum = latency_sum + latency;
	        latencyList.add(new Double(latency));
	        latency_min = Math.min(latency_min, latency);
	        latency_max = Math.max(latency_max, latency);
	        filenameList.add(filename);
	        sinkCreationTimeList.add(new Long(sinkCreationTime));
	        sourceCreationTimeList.add(new Long(sourceCreationTime));
	        cumulativeNumberOfFiles = cumulativeNumberOfFiles + 1;
	        cumulativeNumFilesList.add(new Integer(cumulativeNumberOfFiles));
	        sequenceNumberList.add(new Integer(sequenceNumber));
	        if (sequenceNumber != (previousIndex + 1)) {
	        	outOfOrderList.add(Boolean.valueOf(true));
	        } else {
	        	outOfOrderList.add(Boolean.valueOf(false));
	        }
	        previousIndex = sequenceNumber;
    	}
    	
    	// Calculate latency statistics (average and standard deviation)
    	int num_entries = latencyList.size();
    	double latency_avg = latency_sum / num_entries;
    	double latency_variance = 0.0;
    	for (double next_latency: latencyList) {
    		latency_variance = latency_variance + Math.pow((next_latency-latency_avg), 2.0);
    	}
    	latency_variance = latency_variance / ((double)num_entries);
    	double latency_stddev = Math.sqrt(latency_variance);
    	
    	// Calculate normalized source and sink creation times
    	for (int i=0; i<num_entries; ++i) {
	        long sourceCreationTime = sourceCreationTimeList.get(i).longValue();
	    	long sinkCreationTime = sinkCreationTimeList.get(i).longValue();
	    	double normalizedSourceCreationTime = (sourceCreationTime - earliestSourceCreationTime)/1000.0;
	        normalizedSourceCreationTimeList.add(new Double(normalizedSourceCreationTime));
	        double normalizedSinkCreationTime = (sinkCreationTime - earliestSourceCreationTime)/1000.0;
	        normalizedSinkCreationTimeList.add(new Double(normalizedSinkCreationTime));
	    }
    	
    	// Check that the data arrays are all the same length
    	if ( (latencyList.size() != num_entries) ||
    		 (filenameList.size() != num_entries) ||
    		 (sinkCreationTimeList.size() != num_entries) ||
    		 (normalizedSinkCreationTimeList.size() != num_entries) ||
    		 (sourceCreationTimeList.size() != num_entries) ||
    		 (normalizedSourceCreationTimeList.size() != num_entries) ||
    		 (cumulativeNumFilesList.size() != num_entries) ||
    		 (sequenceNumberList.size() != num_entries) ||
    		 (outOfOrderList.size() != num_entries) )
    	{
    		System.err.println("ERROR: data arrays are not the same size!");
    		return;
    	}
    	
    	//
    	// Calculate test metrics:
    	//     o actual source rate
    	//     o Rku (files/sec)
    	//     o Lav (sec)
    	//     o Lmax (sec)
    	//     o Lgr (sec/file)
    	//     o Lmax (sec) = maximum latency = Lav + 3 * (stddev of latency)
    	//
    	// To calculate these we need the following linear regressions:
    	// 1. index from file vs. create time at source, normalized
    	//       slope = the actual source rate
    	// 2. cumulative number of files at sink vs. create time at sink, normalized
    	//       slope = keep up rate, Rku (files/sec)
    	//       only applicable for a "fast" file test
    	// 3. latency vs. create time at source, normalized (sec)
    	//       y-intercept = average latency, Lav (sec)
    	//       only applicable for a "slow" file test
    	// 4. latency vs. index from file
    	//       slope = latency growth rate, Lgr (sec/file)
    	//       only applicable for a "slow" file test
    	//
    	// We use the Simple regression class available from Apache Commons Math:
    	// http://commons.apache.org/proper/commons-math/userguide/stat.html#a1.4_Simple_regression
    	// http://commons.apache.org/proper/commons-math/apidocs/org/apache/commons/math4/stat/regression/SimpleRegression.html
    	//
    	SimpleRegression actual_source_rate_reg = new SimpleRegression();
    	SimpleRegression Rku_reg = new SimpleRegression();
    	SimpleRegression Lav_reg = new SimpleRegression();
    	SimpleRegression Lgr_reg = new SimpleRegression();
    	for (int i=0; i<num_entries; ++i) {
    		// Here's the data columns written out in order
    		// filenameList.get(i)
    		// sourceCreationTimeList.get(i).longValue()
    		// normalizedSourceCreationTimeList.get(i).doubleValue()
    		// sinkCreationTimeList.get(i).longValue()
    		// normalizedSinkCreationTimeList.get(i).doubleValue()
    		// latencyList.get(i).doubleValue()
    		// cumulativeNumFilesList.get(i).intValue()
    		// sequenceNumberList.get(i).intValue()
    		// outOfOrderList.get(i).booleanValue()
    		actual_source_rate_reg.addData(normalizedSourceCreationTimeList.get(i).doubleValue(), (double)sequenceNumberList.get(i).intValue());
        	Rku_reg.addData(normalizedSinkCreationTimeList.get(i).doubleValue(), (double)cumulativeNumFilesList.get(i).intValue());
        	Lav_reg.addData(normalizedSourceCreationTimeList.get(i).doubleValue(), latencyList.get(i).doubleValue());
        	Lgr_reg.addData((double)sequenceNumberList.get(i).intValue(), latencyList.get(i).doubleValue());
	    }
    	double Lmax = Lav_reg.getIntercept() + 3.0 * (latency_stddev);
    	
    	//
    	// If we are in recaster mode, see if user wants to output results
    	//
    	if ( bRecast && !bOutputResultsInRecastMode ) {
    		// we're done
    		return;
    	}
    	
    	//
    	// Write out data to console
    	//
    	System.err.println("\nTest metrics:");
    	System.err.println("Actual source rate = " + String.format("%5g",actual_source_rate_reg.getSlope()) + " files/sec");
    	System.err.println("Metrics applicable to \"fast\" file tests:");
    	System.err.println("   Rku = " + String.format("%5g",Rku_reg.getSlope()) + " files/sec");
    	System.err.println("Metrics applicable to \"slow\" file tests:");
    	final double latencyMultiplier;
    	if (bAdjustLatencyMetrics) {
    		System.err.println("(NOTE: these latency metrics have been divided by 2 to represent the equivalent one-way metrics values)");
    		latencyMultiplier = 0.5;
    	} else {
    		latencyMultiplier = 1.0;
    	}
    	System.err.println("   Lav = " + String.format("%5g",Lav_reg.getIntercept()*latencyMultiplier) + " sec");
    	System.err.println("   Lmax observed = " + String.format("%.3f",latency_max*latencyMultiplier) + " sec");
    	System.err.println("   Lmax calculated (Lav + 3*(stddev of latency)) = " + String.format("%.3f",Lmax*latencyMultiplier) + " sec");
    	System.err.println("   Lgr = " + String.format("%5g",Lgr_reg.getSlope()*latencyMultiplier) + " sec/file");
    	
    	//
    	// Write out data to file
    	//
    	BufferedWriter writer = null;
    	try {
    		
    	    File dataFile=new File(filenameI);
    	    writer = new BufferedWriter(new FileWriter(dataFile));
    	    
    	    // Write header, including statistics and metrics
    	    writer.write("\nFile sharing test\ntest start time\t" + earliestSourceCreationTime + "\tmsec since epoch\n");
    	    if (bAdjustLatencyMetrics) {
        		writer.write("NOTE: the latency metrics that follow have been divided by 2 to represent the equivalent one-way metrics values; Regression values and the raw data below represent the full round-trip values\n");
    	    }
    	    writer.write("Actual source rate\t"                            + String.format("%5g",actual_source_rate_reg.getSlope())        + "\tfiles/sec\n");
    	    writer.write("Rku\t"                                           + String.format("%5g",Rku_reg.getSlope())                       + "\tfiles/sec\n");
    	    writer.write("Lav\t"                                           + String.format("%5g",Lav_reg.getIntercept()*latencyMultiplier) + "\tsec\n");
    	    writer.write("Lmax observed\t"                                 + String.format("%.3f",latency_max*latencyMultiplier)           + "\tsec\n");
    	    writer.write("std dev of latency\t"                            + String.format("%.3f",latency_stddev*latencyMultiplier)        + "\tsec\n");
    	    writer.write("Lmax calculated (Lav + 3*(stddev of latency))\t" + String.format("%.3f",Lmax*latencyMultiplier)                  + "\tsec\n");
    	    writer.write("Lgr\t"                                           + String.format("%5g",Lgr_reg.getSlope()*latencyMultiplier)     + "\tsec/file\n");
    	    // Write out regression data
    	    writer.write("Regression\tslope\tintercept\tR-squared\tMetric of interest\n\t\t\t\tname\tvalue\n");
    	    double slope = actual_source_rate_reg.getSlope();
    	    double intercept = actual_source_rate_reg.getIntercept();
    	    double rsquared = actual_source_rate_reg.getRSquare();
    	    writer.write("source rate\t" + String.format("%5g",slope) + "\t" + String.format("%5g",intercept) + "\t" + String.format("%5g",rsquared) + "\tsource rate, files/sec (slope)\t" + String.format("%5g",slope) + "\n");
    	    slope = Rku_reg.getSlope();
    	    intercept = Rku_reg.getIntercept();
    	    rsquared = Rku_reg.getRSquare();
    	    writer.write("actual sink rate\t" + String.format("%5g",slope) + "\t" + String.format("%5g",intercept) + "\t" + String.format("%5g",rsquared) + "\tRku, files/sec (slope)\t" + String.format("%5g",slope) + "\n");
    	    slope = Lav_reg.getSlope();
    	    intercept = Lav_reg.getIntercept();
    	    rsquared = Lav_reg.getRSquare();
    	    writer.write("latency vs create time at source\t" + String.format("%5g",slope) + "\t" + String.format("%5g",intercept) + "\t" + String.format("%5g",rsquared) + "\tLav, avg latency, sec (intercept)\t" + String.format("%5g",intercept) + "\n");
    	    slope = Lgr_reg.getSlope();
    	    intercept = Lgr_reg.getIntercept();
    	    rsquared = Lgr_reg.getRSquare();
    	    writer.write("latency vs index from file\t" + String.format("%5g",slope) + "\t" + String.format("%5g",intercept) + "\t" + String.format("%5g",rsquared) + "\tLgr, latency growth, sec/file (slope)\t" + String.format("%5g",slope) + "\n");
    	    writer.write("\nFilename\tCreate time at source (msec)\tCreate time at source, normalized (sec)\tCreate time at sink (msec)\tCreate time at sink, normalized (sec)\tLatency (sec)\tCumulative number of files at sink\tIndex from file\tOut of order or missing?\n");
    	    
    	    // Write data for each file
    	    for (int i=0; i<num_entries; ++i) {
    	        writer.write(
    	        	filenameList.get(i) + "\t" +
    	            sourceCreationTimeList.get(i).longValue() + "\t" +
    	        	String.format("%.3f",normalizedSourceCreationTimeList.get(i).doubleValue()) + "\t" +
    	        	sinkCreationTimeList.get(i).longValue() + "\t" +
    	        	String.format("%.3f",normalizedSinkCreationTimeList.get(i).doubleValue()) + "\t" +
    	            String.format("%.3f",latencyList.get(i).doubleValue()) + "\t" +
    	            cumulativeNumFilesList.get(i).intValue() + "\t" +
    	            sequenceNumberList.get(i).intValue() + "\t" +
    	            outOfOrderList.get(i).toString() + "\n");
    	        writer.flush();
    	    }
    	    
    	} catch (Exception e) {
    		e.printStackTrace();
    	}
    	
    	try {
    	    writer.close();
    	} catch (IOException e) {
    		// nothing to do
    	}
    	
    	//
    	// Display plots
    	//
    	if (bAdjustLatencyMetrics) {
    		latencyPlotTitle = "Latency (one-way)";
    	}
    	if ( (customPlotTitle != null) && (!customPlotTitle.isEmpty()) ) {
    		latencyPlotTitle = new String(customPlotTitle + ", " + latencyPlotTitle);
    		throughputPlotTitle = new String(customPlotTitle + ", " + throughputPlotTitle);
    	}
    	SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
            	// For the plot, multiply latency data by latencyMultiplier, which will either leave the
            	// latency data as-is or will divide it by 2 (if user wanted the adjusted, one-way equivalent data)
            	List<Double> adjustedLatencyList = new ArrayList<Double>();
            	for (int i=0; i<num_entries; ++i) {
            		adjustedLatencyList.add(latencyList.get(i)*latencyMultiplier);
            	}
            	String yAxisTitle = "Latency (sec)";
            	if (bAdjustLatencyMetrics) {
            		yAxisTitle = "Latency, one-way equivalent (sec)";
            	}
            	new DisplayPlot(
            		latencyPlotTitle,
            	    "Create time at source (sec)",
            	    yAxisTitle,
            	    normalizedSourceCreationTimeList,
            	    adjustedLatencyList,
            	    300,300)
            	.setVisible(true);
            	// Need to convert cumulativeNumFilesList to List<Double> to send it to the plot routine
            	List<Double> doubleList = new ArrayList<Double>();
            	for (int i=0; i<num_entries; ++i) {
            		doubleList.add(new Double((double)cumulativeNumFilesList.get(i).intValue()));
            	}
            	new DisplayPlot(
            		throughputPlotTitle,
                	"Create time at sink (sec)",
                	"Number of files at sink",
                	normalizedSinkCreationTimeList,
                	doubleList,
                	400,400)
                .setVisible(true);
            }
        });
    	
    }
    
    /**
     * Incoming filenames should have the following format:
     * 
     * 		<source_creation_time>_<sequence_num>.txt
     * 
     * This function extracts <source_creation_time> and <sequence_num> and
     * stores them in a FilenameInfo object, which this method returns.
     * If any error occurs, the errStr field in the FilenameInfo object
     * will contain the error message.
     */
    FilenameInfo checkFilename(String filenameI) {
    	
    	FilenameInfo fiObj = new FilenameInfo();
    	
        int tot_len = filenameI.length();
        
        // 1. Filename should end in ".txt"
        if (!filenameI.endsWith(".txt")) {
        	fiObj.errStr = new String("Skipping file with unrecognized filename format: " + filenameI);
        	return fiObj;
        }
        
        // 2. Filename should contain one "_" in between two numbers
        int underscore_idx = filenameI.indexOf('_');
        int last_underscore_idx = filenameI.lastIndexOf('_');
        if ( (underscore_idx == -1) || (underscore_idx != last_underscore_idx) ) {
        	fiObj.errStr = new String("Skipping file with unrecognized filename format: " + filenameI);
        	return fiObj;
        }
        
        String sourceCreationTimeStr = filenameI.substring(0,underscore_idx);
        long sourceCreationTime = 0;
        try {
            sourceCreationTime = (new Long(sourceCreationTimeStr)).longValue();
        } catch (NumberFormatException e) {
        	fiObj.errStr = new String("Skipping file with unrecognized filename format: " + filenameI);
        	return fiObj;
        }
        
        String sequenceNumberStr = filenameI.substring(underscore_idx+1,tot_len-4);
        int sequenceNumber = 0;
        try {
        	sequenceNumber = new Integer(sequenceNumberStr).intValue();
        } catch (NumberFormatException e) {
        	fiObj.errStr = new String("Skipping file with unrecognized filename format: " + filenameI);
        	return fiObj;
        }
        
        fiObj.sourceCreationTime = sourceCreationTime;
        fiObj.sequenceNumber = sequenceNumber;
    	
    	return fiObj;
    }
    
    public static void main(String[] args) throws IOException {
        
        new FileWatch(args);
        
    }
}
