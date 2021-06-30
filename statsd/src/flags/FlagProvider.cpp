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

#include "FlagProvider.h"

using android::modules::sdklevel::IsAtLeastS;
using server_configurable_flags::GetServerConfigurableFlag;
using std::string;

namespace android {
namespace os {
namespace statsd {

FlagProvider::FlagProvider()
    : mIsAtLeastSFunc(IsAtLeastS), mGetServerFlagFunc(GetServerConfigurableFlag) {
}

FlagProvider& FlagProvider::getInstance() {
    static FlagProvider instance;
    return instance;
}

string FlagProvider::getFlagString(const string& flagName, const string& defaultValue) const {
    if (!mIsAtLeastSFunc()) {
        return defaultValue;
    }
    return mGetServerFlagFunc(STATSD_NATIVE_NAMESPACE, flagName, defaultValue);
}

bool FlagProvider::getFlagBool(const string& flagName, const string& defaultValue) const {
    return getFlagString(flagName, defaultValue) == FLAG_TRUE;
}

void FlagProvider::overrideFuncs(const IsAtLeastSFunc& isAtLeastSFunc,
                                 const GetServerFlagFunc& getServerFlagFunc) {
    mIsAtLeastSFunc = isAtLeastSFunc;
    mGetServerFlagFunc = getServerFlagFunc;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
