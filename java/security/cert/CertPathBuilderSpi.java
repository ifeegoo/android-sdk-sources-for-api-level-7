/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.security.cert;

import java.security.InvalidAlgorithmParameterException;

/**
 * The <i>Service Provider Interface</i> (<b>SPI</b>) for the {@code
 * CertPathBuilder} class to be implemented by security providers.
 * 
 * @since Android 1.0
 */
public abstract class CertPathBuilderSpi {

    /**
     * Creates a new {@code CertPathBuilderSpi} instance.
     * 
     * @since Android 1.0
     */
    public CertPathBuilderSpi() {
    }

    /**
     * Builds a certification path with the specified algorithm parameters.
     * 
     * @param params
     *            the algorithm parameters.
     * @return a result of the build.
     * @throws CertPathBuilderException
     *             if the build fails.
     * @throws InvalidAlgorithmParameterException
     *             if the specified parameters cannot be used to build the path
     *             with this builder.
     * @since Android 1.0
     */
    public abstract CertPathBuilderResult engineBuild(CertPathParameters params)
            throws CertPathBuilderException, InvalidAlgorithmParameterException;
}
