/*
 * Copyright (c) 2013 Allogy Interactive.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.allogy.build.maven;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.google.common.base.Optional;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.Repository;
import org.kuali.maven.wagon.auth.AuthenticationInfoCredentialsProvider;
import org.kuali.maven.wagon.auth.AwsCredentials;
import org.kuali.maven.wagon.auth.AwsSessionCredentials;

public class S3Wagon extends org.kuali.maven.wagon.S3Wagon
{
    @Override
    protected CannedAccessControlList getAclFromRepository(Repository repository)
    {
        return CannedAccessControlList.Private;
    }

    @Override
    protected AWSCredentials getCredentials(final AuthenticationInfo authenticationInfo)
    {
        Optional<AuthenticationInfo> auth = Optional.fromNullable(authenticationInfo);
        AuthenticationInfoCredentialsProvider authenticationInfoCredentialsProvider = new AuthenticationInfoCredentialsProvider(auth);

        DefaultAWSCredentialsProviderChain defaultChain = new DefaultAWSCredentialsProviderChain();

        AWSCredentialsProviderChain aggregateChain =
                new AWSCredentialsProviderChain(authenticationInfoCredentialsProvider, defaultChain);

        AWSCredentials credentials = aggregateChain.getCredentials();
        if (credentials instanceof AWSSessionCredentials)
        {
            return new AwsSessionCredentials((AWSSessionCredentials) credentials);
        }
        else
        {
            return new AwsCredentials(credentials);
        }
    }
}
