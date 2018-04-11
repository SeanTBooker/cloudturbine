import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Toolkit;
import java.util.LinkedHashMap;
import java.util.Map;

import cycronix.ctlib.*;

/**
 * CloudTurbine demo source
 * Track mouse position and output x,y tracks
 * <p>
 * @author Matt Miller (MJM), Cycronix
 * @version 2016/09/13
 * 
*/

/*
Copyright 2018 Cycronix

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

public class CTmousetrack {
	public static void main(String[] args) {
		String dstFolder = "";
		
		if(args.length > 0) dstFolder = args[0];
		else				dstFolder = "CTmousetrack";
		
		long blockPts = 10;			// points per block flush
		if(args.length > 1) blockPts = Integer.parseInt(args[1]);
		
		long sampInterval = 10;			// msec between sampling updates
		if(args.length > 2) sampInterval = Integer.parseInt(args[2]);

		double trimTime = 0.;
		if(args.length > 3) trimTime = Double.parseDouble(args[3]);
		
		System.err.println("CTmousetrack <dstFolder> <blockPts("+blockPts+")> <sampInterval("+sampInterval+")> <trimTime("+trimTime+")>");

		try {
			// setup CTwriter
			CTwriter ctw = new CTwriter(dstFolder, trimTime);	// trim old data

			CTinfo.setDebug(false);
			ctw.setBlockMode(blockPts>1,blockPts>1);		// pack, zip
			ctw.autoFlush(0);								// no autoflush, no segments
			ctw.autoSegment(1000);
			
			// screen dims
			Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
			double width = screenSize.getWidth();
			double height = screenSize.getHeight();
			
			// use Map for consolidated putData
			Map<String,Object>cmap = new LinkedHashMap<String,Object>();
			
			// loop and write some output
			for(int i=0; i<1000000; i++) {						// go until killed
				ctw.setTime(System.currentTimeMillis());				
				cmap.clear(); 
				
				Point mousePos = MouseInfo.getPointerInfo().getLocation();
				
				cmap.put("x", (float)(mousePos.getX()/width));					// normalize
				cmap.put("y", (float)((height-mousePos.getY())/height));		// flip Y (so bottom=0)
				ctw.putData(cmap);
				
				if(((i+1)%blockPts)==0) {
					ctw.flush();
					System.err.print(".");
				}
				try { Thread.sleep(sampInterval); } catch(Exception e) {};
			}
			ctw.flush(); 	// wrap up
		} catch(Exception e) {
			System.err.println("CTsource exception: "+e);
			e.printStackTrace();
		} 
	}
}

