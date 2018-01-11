/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.cts.statsd.metric;

import com.android.internal.os.StatsdConfigProto;

public class MetricsUtils {
    public static final long COUNT_METRIC_ID = 3333;

    public static StatsdConfigProto.StatsdConfig.Builder getEmptyConfig() {
        StatsdConfigProto.StatsdConfig.Builder builder =
                StatsdConfigProto.StatsdConfig.newBuilder();
        // only accept the log events from this cts to avoid noise.
        builder.addAllowedLogSource("com.android.server.cts.device.statsd");
        return builder;
    }

    public static StatsdConfigProto.AtomMatcher.Builder getAtomMatcher(int atomTag) {
        StatsdConfigProto.AtomMatcher.Builder builder = StatsdConfigProto.AtomMatcher.newBuilder();
        builder.setSimpleAtomMatcher(StatsdConfigProto.SimpleAtomMatcher.newBuilder()
                        .setAtomId(atomTag).build());
        return builder;
    }
}
