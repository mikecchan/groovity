/*******************************************************************************
 * © 2018 Disney | ABC Television Group
 *
 * Licensed under the Apache License, Version 2.0 (the "Apache License")
 * with the following modification; you may not use this file except in
 * compliance with the Apache License and the following modification to it:
 * Section 6. Trademarks. is deleted and replaced with:
 *
 * 6. Trademarks. This License does not grant permission to use the trade
 *     names, trademarks, service marks, or product names of the Licensor
 *     and its affiliates, except as required to comply with Section 4(c) of
 *     the License and to reproduce the content of the NOTICE file.
 *
 * You may obtain a copy of the Apache License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License with the above modification is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the Apache License for the specific
 * language governing permissions and limitations under the Apache License.
 *******************************************************************************/
package com.disney.http.auth.server.digest;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;

/**
 * API used in validating Digest authentication; providers need to be able to respond with
 * md5(username:realm:password), which might have been precomputed before storage, or may require
 * looking up the password and regenerating the digest.  Should return null if this digester has no record
 * for the given username+realm
 * 
 * @author Alex Vigdor
 *
 */
public interface PasswordDigester {
	public byte[] digest(String username, String realm) throws NoSuchAlgorithmException, UnsupportedEncodingException;
}
