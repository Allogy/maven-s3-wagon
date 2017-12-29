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

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

import java.util.UUID;

import static com.amazonaws.SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY;
import static com.amazonaws.SDKGlobalConfiguration.SECRET_KEY_SYSTEM_PROPERTY;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class S3WagonTest
{
    @After
    public void tearDown()
    {
        System.clearProperty(ACCESS_KEY_SYSTEM_PROPERTY);
        System.clearProperty(SECRET_KEY_SYSTEM_PROPERTY);
    }

    private S3Wagon createObjectUnderTest()
    {
        return new S3Wagon();
    }

    @Test
    public void getAclFromRepository_should_return_Private_Acl()
    {
        assertThat(createObjectUnderTest().getAclFromRepository(null), is(CannedAccessControlList.Private));
    }

    @Test(expected = AmazonClientException.class)
    @Ignore("The CI build may run with credentials from the chain. So this test doesn't build there.")
    public void getCredentials_with_null_authenticationInfo_and_nothing_for_default_chain_should_throw()
    {
        createObjectUnderTest().getCredentials(null);
    }

    @Test
    public void getCredentials_with_null_authenticationInfo_and_a_known_DefaultProviderChain_available_should_return_from_Default()
    {
        // Configure to use SystemPropertiesCredentialsProvider since that is easy to simulate
        String accessKey = UUID.randomUUID().toString();
        String secretKey = UUID.randomUUID().toString();

        System.setProperty(ACCESS_KEY_SYSTEM_PROPERTY, accessKey);
        System.setProperty(SECRET_KEY_SYSTEM_PROPERTY, secretKey);

        AWSCredentials credentials = createObjectUnderTest().getCredentials(null);

        assertThat(credentials, notNullValue());
        assertThat(credentials.getAWSAccessKeyId(), is(accessKey));
        assertThat(credentials.getAWSSecretKey(), is(secretKey));
    }

    @Test
    public void getCredentials_with_authenticationInfo_should_return_those_credentials()
    {
        String accessKey = UUID.randomUUID().toString();
        String secretKey = UUID.randomUUID().toString();
        AuthenticationInfo authenticationInfo = mock(AuthenticationInfo.class);
        when(authenticationInfo.getUserName()).thenReturn(accessKey);
        when(authenticationInfo.getPassword()).thenReturn(secretKey);

        AWSCredentials credentials = createObjectUnderTest().getCredentials(authenticationInfo);

        assertThat(credentials, notNullValue());
        assertThat(credentials.getAWSAccessKeyId(), is(accessKey));
        assertThat(credentials.getAWSSecretKey(), is(secretKey));
    }
}
