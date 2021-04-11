/*
 * Copyright (C) 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "kll.h"

#include <cstdint>
#include <memory>

#include "compactor_stack.h"

namespace dist_proc {
namespace aggregation {

std::unique_ptr<KllQuantile> KllQuantile::Create(std::string* error) {
    return Create(KllQuantileOptions(), error);
}

std::unique_ptr<KllQuantile> KllQuantile::Create(const KllQuantileOptions& options,
                                                 std::string* error) {
    if (options.k() < 0) {
        if (error != nullptr) {
            *error = "k has to be >= 0";
        }
        return nullptr;
    }
    return std::unique_ptr<KllQuantile>(
            new KllQuantile(options.inv_eps(), options.inv_delta(), options.k(), options.random()));
}

void KllQuantile::Add(const int64_t value) {
    compactor_stack_.Add(value);
    UpdateMin(value);
    UpdateMax(value);
    num_values_++;
}

void KllQuantile::AddWeighted(int64_t value, int weight) {
    if (weight > 0) {
        compactor_stack_.AddWithWeight(value, weight);
        UpdateMin(value);
        UpdateMax(value);
        num_values_ += weight;
    }
}

void KllQuantile::UpdateMin(int64_t value) {
    if (num_values_ == 0 || min_ > value) {
        min_ = value;
    }
}

void KllQuantile::UpdateMax(int64_t value) {
    if (num_values_ == 0 || max_ < value) {
        max_ = value;
    }
}

void KllQuantile::Reset() {
    num_values_ = 0;
    compactor_stack_.Reset();
}

}  // namespace aggregation
}  // namespace dist_proc
