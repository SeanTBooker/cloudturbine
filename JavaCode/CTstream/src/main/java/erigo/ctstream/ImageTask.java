/*
Copyright 2017-2018 Erigo Technologies LLC

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

package erigo.ctstream;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.TimerTask;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

import org.openimaj.image.ImageUtilities;

/**
 * Generate an image for a DataStream and put it in the DataStream's queue.
 *
 * @author John P. Wilson
 * @version 03/07/2018
 *
 */

public class ImageTask extends TimerTask implements Runnable {
	
	private CTstream cts = null;
	private DataStream dataStream = null;
	ImageStreamSpec spec = null;
	private JPEGImageWriteParam jpegParams = null; // to specify image quality

	// (MJM) static variables used to implement the "change detect" feature
	static BufferedImage oldImage = null;
	static long oldImageTime = 0;
	static long skipChangeDetectDelay = 1000; // don't drop images for longer than this delay
	
	// Constructor
	public ImageTask(CTstream ctsI, DataStream dataStreamI, ImageStreamSpec specI) {
		cts = ctsI;
		dataStream = dataStreamI;
		spec = specI;
		// Setup image quality
		// Code copied from JeanValjean's sample code found on Stack Overflow at
		//     http://stackoverflow.com/questions/17108234/setting-jpg-compression-level-with-imageio-in-java
		// Sample author: JeanValjean, https://stackoverflow.com/users/1339429/jeanvaljean
		// License: Stack Overflow content is covered by the Creative Commons license, https://creativecommons.org/licenses/by-sa/3.0/legalcode
		jpegParams = new JPEGImageWriteParam(null);
		jpegParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		jpegParams.setCompressionQuality(spec.imageQuality);
	}

	//
	// Perform the work of generating the image.
	//
	public void run() {
		
		// Check how much time it actually takes to perform the image capture
		long startTime = System.currentTimeMillis();

		// The max amount of time we'd want the image capture to take
		long capturePeriodMillis = 0;
		
		if (!dataStream.bIsRunning) {
			return;
		}
		
		try {
			BufferedImage bufferedImage = null;
			if (dataStream instanceof WebcamStream) {
				capturePeriodMillis = ((WebcamStream)dataStream).capturePeriodMillis;
				if ( (WebcamStream.webcam == null) /* || !WebcamStream.webcam.isOpen() */) {
					// Web camera hasn't been opened yet; just return
					return;
				}
//				bufferedImage = WebcamStream.webcam.getImage();
	        	bufferedImage = ImageUtilities.createBufferedImageForDisplay(WebcamStream.webcam.getNextFrame());
			}
			else if (dataStream instanceof ScreencapStream) {
				capturePeriodMillis = ((ScreencapStream)dataStream).capturePeriodMillis;
				// the rectangular region to capture
				Rectangle captureRect = ((ScreencapStream)dataStream).regionToCapture;
				Robot robot = new Robot();
				bufferedImage = robot.createScreenCapture(captureRect);
				if (((ScreencapStreamSpec)spec).bIncludeMouseCursor) {
					// Need to add the mouse cursor to the image
					int mouse_x = MouseInfo.getPointerInfo().getLocation().x;
					int mouse_y = MouseInfo.getPointerInfo().getLocation().y;
					int cursor_x = mouse_x - captureRect.x;
					int cursor_y = mouse_y - captureRect.y;
					Graphics2D graphics2D = bufferedImage.createGraphics();
					graphics2D.drawImage(((ScreencapStream)dataStream).cursor_img, cursor_x, cursor_y, null);
				}
			}

			// Implement "change detect" logic where we only save images that have changed
			// Check for changes if:
			// - User has indicated they want to use "Change detect"
			// - We are within skipChangeDetectDelay milliseconds of the last image being saved
			if(spec.bChangeDetect && startTime < (oldImageTime+skipChangeDetectDelay)) {
				// check to see if the image has changed
				if (imageSame(bufferedImage, oldImage)) {
					// no change, just return
					return;
				}
			}
			oldImage = bufferedImage;
			oldImageTime = startTime;

			//
			// Convert image to byte array and save it in the DataStream's queue
			//
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			// Write out the image using default compression
			// ImageIO.write(bufferedImage, "jpg", baos);
			// Code to write out the image at a custom compression/quality level is based on submissions the following Stack Overflow post:
			// http://stackoverflow.com/questions/13204432/java-how-to-set-jpg-quality
			// Contributions from the following authors:
			//     NestedCodeblocksFTW, https://stackoverflow.com/users/1681751/nestedcodeblocksftw
			//     lmiguelmh, https://stackoverflow.com/users/2692914/lmiguelmh
			//     Hoku, https://stackoverflow.com/users/1214371/hoku
			// License: Stack Overflow content is covered by the Creative Commons license, https://creativecommons.org/licenses/by-sa/3.0/legalcode
			ImageOutputStream  ios =  ImageIO.createImageOutputStream(baos);
		    Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("jpg");
		    ImageWriter writer = iter.next();
		    writer.setOutput(ios);
		    writer.write(null, new IIOImage(bufferedImage,null,null),jpegParams);
		    writer.dispose();
			baos.flush();
			byte[] jpegByteArray = baos.toByteArray();
			if ( (jpegByteArray == null) || (jpegByteArray.length == 0) ) {
				return;
			}
			baos.close();
			// Add baos to the queue of yet-to-be-sent-to-CT images
			// NOTE: we use getNextTime() here to support continue mode
			try {
				dataStream.queue.put(new TimeValue(cts.getNextTime(), jpegByteArray));
				if (dataStream instanceof ScreencapStream) {
					if (cts.bPrintDataStatusMsg) { System.err.print("s"); }
				} else if (dataStream instanceof WebcamStream) {
					if (cts.bPrintDataStatusMsg) { System.err.print("w"); }
				}
			} catch (Exception e) {
				if (dataStream.bIsRunning) {
					System.err.println("\nImageTask: exception thrown adding image to queue:\n" + e);
					e.printStackTrace();
				}
				return;
			}

			//
			// Display preview image
			//
			if (dataStream.bPreview && (dataStream.previewWindow != null) ) {
				if ( (dataStream instanceof ScreencapStream) && ((ScreencapStreamSpec)spec).bFullScreen ) {
					// In full screen mode, we will pop down the preview window
					dataStream.updatePreview();
				} else {
					// To view the image with the correct JPEG compression, need to send PreviewWindow a new
					// BufferedImage based on jpegByteArray; can't just send bufferedImage because that image
					// hasn't been compressed yet.
					dataStream.previewWindow.updateImage(ImageIO.read(new ByteArrayInputStream(jpegByteArray)));
				}
			}
		} catch (Exception e) {
			if (dataStream.bIsRunning) {
				// Only print out error messages if we know we should still be running
			    System.err.println("\nError generating image:\n" + e);
			    e.printStackTrace();
			}
			return;
		}
		
		long capTime = System.currentTimeMillis() - startTime;
		if (capTime > capturePeriodMillis) {
			System.err.println("\nWARNING: " + capTime + " msec to capture " + dataStream.channelName + " image (" + capturePeriodMillis + " msec is the desired period)");
		}
		
	}
	
	// See if two images are identical bitmaps - MJM
	static boolean imageSame(BufferedImage img1, BufferedImage img2) {
		if(img1==null || img2==null) return false;
		int w1 = img1.getWidth(null);
		int w2 = img2.getWidth(null);
		int h1 = img1.getHeight(null);
		int h2 = img2.getHeight(null);
		if ((w1 != w2) || (h1 != h2)) return false;

		for (int y = 0; y < h1; y++) {
			for (int x = 0; x < w1; x++) {
				int rgb1 = img1.getRGB(x, y);
				int rgb2 = img2.getRGB(x, y);
				if(rgb1 != rgb2) return false;
			}
		}
		return true;
	}
}
