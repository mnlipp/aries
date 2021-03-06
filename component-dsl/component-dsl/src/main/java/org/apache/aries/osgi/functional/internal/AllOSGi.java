/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.aries.osgi.functional.internal;

import org.apache.aries.osgi.functional.OSGi;
import org.apache.aries.osgi.functional.OSGiResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author Carlos Sierra Andrés
 */
public class AllOSGi<T> extends OSGiImpl<T> {

    @SafeVarargs
    public AllOSGi(OSGi<T>... programs) {
        super((bundleContext, op) -> {
            List<OSGiResult> results = new ArrayList<>();

            return new OSGiResultImpl(
                () -> {
                    results.addAll(
                        Arrays.stream(programs).
                            map(o -> ((OSGiImpl<T>) o)._operation.run(
                                bundleContext, op)).
                            collect(Collectors.toList()));

                    results.forEach(osGiResult -> {
                        try {
                            osGiResult.start();
                        }
                        catch (Exception e) {}
                    });
                },
                () -> {
                    for (OSGiResult result : results) {
                        try {
                            result.close();
                        }
                        catch (Exception ignored) {
                        }
                    }
                }
            );
        });
    }
}
