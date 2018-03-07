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
import java.util.concurrent.BlockingQueue;

/**
 * Abstract class to act as the parent of all data stream classes which send data to CloudTurbine.
 *
 * @author John Wilson
 * @version 03/07/2018
 */

public abstract class DataStream {

    // ID to uniquely identify this DataStream
    public int id = -1;

    // Specifications for running the data stream
    public DataStreamSpec spec = null;

    public String channelName = "";

    public BlockingQueue<TimeValue> queue = null;

    public CTstream cts = null;

    // Specify the type of data managed by this DataStream's PreviewWindow.
    private PreviewWindow.PreviewType previewType = PreviewWindow.PreviewType.IMAGE;

    // If bManualFlush is true, CTwriter.flush() will be called after data from this DataStream is sent to CT;
    // if no DataStream has manual flush, then WriteTask will call flush at the period indicated by the user
    // in the Settings dialog (CTstream.flushMillis)
    public boolean bManualFlush = false;

    // Can this DataStream display a preview window?
    public boolean bCanPreview = false;

    // If this DataStream can display a preview window, should it
    public boolean bPreview = false;

    public PreviewWindow previewWindow = null;

    // Is this stream currently running?
    public boolean bIsRunning = false;

    /**
     * DataStream constructor
     *
     * @param previewTypeI  Type of data this DataStream will send to be displayed in a PreviewWindow.
     */
    public DataStream(PreviewWindow.PreviewType previewTypeI) {
        previewType = previewTypeI;
        // get an ID from CTstream
        id = CTstream.getNextDataStreamID();
    }

    /**
     * Start the stream
     * @throws Exception if there is any problem starting the DataStream
     */
    public abstract void start() throws Exception;

    /**
     * Stop the stream
     */
    public void stop() {
        bIsRunning = false;
        // Clear the queue
        if (queue != null) {
            queue.clear();
            queue = null;
        }
        updatePreview();
    }

    /**
     * User has changed real-time settings; update the stream to use these new settings
     */
    public void update() {
        if (!bIsRunning) {
            // Not currently running; just return
            return;
        }
        updatePreview();
    }

    /**
     * Manage popping up or down the preview window
     */
    public void updatePreview() {
        if (bIsRunning && spec.bPreview && bCanPreview && !bPreview) {
            // open preview window
            previewWindow = new PreviewWindow(channelName + " preview", new Dimension(400,400), previewType);
            bPreview = true;
        } else if (!spec.bPreview || !bIsRunning) {
            bPreview = false;
            if (previewWindow != null) {
                previewWindow.close();
                previewWindow = null;
            }
        }
    }

}
