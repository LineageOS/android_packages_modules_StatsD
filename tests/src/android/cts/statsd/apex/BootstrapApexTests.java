/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.cts.statsd.apex;

import static com.google.common.truth.Truth.assertThat;

import android.cts.statsd.atom.BaseTestCase;
import com.android.compatibility.common.util.ApiLevelUtil;
import com.android.tradefed.log.LogUtil;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Verify statsd is not in the bootstrap apexes
 */
public class BootstrapApexTests extends BaseTestCase {
    private static final String TAG = "Statsd.BootstrapApexTests";

    // Constants
    private static final String BOOTSTRAP_APEX_FILE = "/apex/.bootstrap-apex-info-list.xml";

    private boolean sdkLevelAtLeast(int sdkLevel, String codename) throws Exception {
        return ApiLevelUtil.isAtLeast(getDevice(), sdkLevel)
                || ApiLevelUtil.codenameEquals(getDevice(), codename);
    }

    public void testStatsdNotPresent() throws Exception {
        if (!sdkLevelAtLeast(31, "S")) {
            return;
        }
        String adbCommand = "cat " + BOOTSTRAP_APEX_FILE;
        String fileContents = getDevice().executeShellCommand(adbCommand);

        LogUtil.CLog.d(TAG + " Received the following file: " + fileContents);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document xml = builder.parse(new InputSource(new StringReader(fileContents)));

        NodeList apexInfoList = xml.getElementsByTagName("apex-info-list");
        assertThat(apexInfoList.getLength()).isEqualTo(1);
        NodeList apexInfoNodes = apexInfoList.item(0).getChildNodes();
        assertThat(apexInfoNodes.getLength()).isGreaterThan(0);

        int numApexes = 0;
        for (int i = 0; i < apexInfoNodes.getLength(); i++) {
            Node apexInfoNode = apexInfoNodes.item(i);
            String name = apexInfoNode.getNodeName();
            if (name.equals("apex-info")) {
                numApexes++;
                NamedNodeMap attr = apexInfoNode.getAttributes();
                Node moduleName = attr.getNamedItem("moduleName");
                assertThat(moduleName).isNotNull();
                assertThat(moduleName.getNodeValue()).isNotEqualTo("com.android.os.statsd");
            }
        }
        assertThat(numApexes).isGreaterThan(0);
    }
}
