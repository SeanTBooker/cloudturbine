/*
Copyright 2018 Erigo Technologies LLC

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

/**
 * Specifications for ScreencapStream.
 *
 * @author John P. Wilson
 * @version 2018-03-06
 */

public class ScreencapStreamSpec extends ImageStreamSpec {

    public boolean bFullScreen = false;         // Capture the entire screen?

    public boolean bIncludeMouseCursor = true;  // Include mouse cursor in the screencap image?
}
