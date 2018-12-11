// =========================================================================
// Copyright 2018 T-Mobile, US
// 
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// See the readme.txt file for additional language around disclaimer of warranties.
// =========================================================================

package com.tmobile.cso.vault.api.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.tmobile.cso.vault.api.model.SafeGroup;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.tmobile.cso.vault.api.controller.ControllerUtil;
import com.tmobile.cso.vault.api.model.Safe;
import com.tmobile.cso.vault.api.model.SafeUser;
import com.tmobile.cso.vault.api.model.UserDetails;
import com.tmobile.cso.vault.api.utils.AuthorizationUtils;
import com.tmobile.cso.vault.api.utils.JSONUtil;
import com.tmobile.cso.vault.api.utils.PolicyUtils;
import com.tmobile.cso.vault.api.utils.SafeUtils;

@Component
public class  SelfSupportService {
	
	@Autowired
	private SafesService safesService;
	
	@Autowired
	private PolicyUtils policyUtils;
	
	@Autowired
	private AuthorizationUtils authorizationUtils;
	
	@Autowired
	private SafeUtils safeUtils;
	
	@Value("${vault.auth.method}")
	private String vaultAuthMethod;

	public static final String READ_POLICY="read";
	public static final String WRITE_POLICY="write";
	public static final String DENY_POLICY="deny";
	public static final String SUDO_POLICY="sudo";
	
	private static Logger log = LogManager.getLogger(SelfSupportService.class);

	/**
	 * Creates a safe by the user with least privileges, Requires an AppRole which can perform Safe Creation 
	 * (Sufficient access to the paths such as shared or metadata/shared, etc)
	 * @param token
	 * @param safe
	 * @return
	 */
	public ResponseEntity<String> createSafe(UserDetails userDetails, String userToken, Safe safe) {
		
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.createSafe(token, safe);
		}
		else {
			// Assign the owner (Infer from logged in user?)
			// Create proper policies
			// Assign the policies
			// Modify should work the same
			// Delete safe - clean up of all items, paths, permissions, policies
			token = userDetails.getSelfSupportToken();
			if (safe != null && safe.getSafeBasicDetails() != null) {
				safe.getSafeBasicDetails().setOwnerid(userDetails.getUsername());
			}
			ResponseEntity<String> safe_creation_response = safesService.createSafe(token, safe);
			if (HttpStatus.OK.equals(safe_creation_response.getStatusCode() )) {
				// Associate admin user to the safe...
				SafeUser safeUser = new SafeUser();
				safeUser.setAccess("sudo");
				safeUser.setPath(safe.getPath());
				safeUser.setUsername(userDetails.getUsername());
				safesService.addUserToSafe(token, safeUser, null);
			}
			return safe_creation_response;
		}
	}
	

	/**
	 * Adds a user to a safe
	 * @param userDetails
	 * @param userToken
	 * @param safeUser
	 * @return
	 */
	public ResponseEntity<String> addUserToSafe(UserDetails userDetails, String userToken, SafeUser safeUser) {
		boolean canAddUser = safeUtils.canAddUser(userDetails, safeUser);
		if (canAddUser) {
			if (userDetails.isAdmin()) {
				return safesService.addUserToSafe(userDetails.getClientToken(), safeUser, null);
			}
			else {
				return safesService.addUserToSafe(userDetails.getSelfSupportToken(), safeUser, userDetails);
			}
		}
		else {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Can't add user. Possible reasons: Invalid path specified, 2. Changing access/permission of safe owner is not allowed\"]}");
		}
	}
	/**
	 * Get SDB Info
	 * @param token
	 * @param path
	 * @return
	 */
	public ResponseEntity<String> getInfo(UserDetails userDetails, String userToken, String path){
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.getInfo(token, path);
		}
		else {
			token = userDetails.getSelfSupportToken();
			return safesService.getInfo(token, path);
		}
	}
	/**
	 * Gets safe information as power user
	 * @param token
	 * @param path
	 * @return
	 */
	public ResponseEntity<String> getSafe(UserDetails userDetails, String userToken, String path) {
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.getSafe(token, path);
		}
		else {
			String safeType = ControllerUtil.getSafeType(path);
			String safeName = ControllerUtil.getSafeName(path);
			if (StringUtils.isEmpty(safeType) || StringUtils.isEmpty(safeName)) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Invalid path specified\"]}");
			}
			String powerToken = userDetails.getSelfSupportToken();
			String username = userDetails.getUsername();
			Safe safeMetaData = safeUtils.getSafeMetaData(powerToken, safeType, safeName);
			String[] latestPolicies = policyUtils.getCurrentPolicies(powerToken, username);
			ArrayList<String> policiesTobeChecked =  policyUtils.getPoliciesTobeCheked(safeType, safeName);
			boolean isAuthorized = authorizationUtils.isAuthorized(userDetails, safeMetaData, latestPolicies, policiesTobeChecked, false);
			if (isAuthorized) {
				token = userDetails.getSelfSupportToken();
				return safesService.getSafe(token, path);
			}
			else {
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"errors\":[\"Not authorized to get Safe information\"]}");
			}
		}
	}
	/**
	 * Removes user from safe as PowerUser
	 * @param token
	 * @param safeUser
	 * @return
	 */
	public ResponseEntity<String> removeUserFromSafe(UserDetails userDetails, String userToken, SafeUser safeUser) {
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.removeUserFromSafe(token, safeUser);
		}
		else {
			token = userDetails.getSelfSupportToken();
			return safesService.removeUserFromSafe(token, safeUser);
		}
	}
	/**
	 * Read from safe Recursively
	 * @param token
	 * @param path
	 * @return
	 */
	public ResponseEntity<String> getFoldersRecursively(UserDetails userDetails, String userToken, String path) {
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.getFoldersRecursively(token, path);
		}
		else {
			// List of safes based on current user
			String[] policies = policyUtils.getCurrentPolicies(userDetails.getSelfSupportToken(), userDetails.getUsername());
			String[] safes = safeUtils.getManagedSafesFromPolicies(policies, path);
			Map<String, String[]> safesMap = new HashMap<String, String[]>();
			safesMap.put("keys", safes);
			return ResponseEntity.status(HttpStatus.OK).body(JSONUtil.getJSON(safesMap));
		}
	}
	/**
	 * isAuthorized
	 * @param token
	 * @param safeName
	 * @return
	 */
	public ResponseEntity<String> isAuthorized (UserDetails userDetails, String path) {
		if (!ControllerUtil.isPathValid(path)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Invalid path specified\"]}");
		}
		String safeType = ControllerUtil.getSafeType(path);
		String safeName = ControllerUtil.getSafeName(path);
		if (StringUtils.isEmpty(safeType) || StringUtils.isEmpty(safeName)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Invalid path specified\"]}");
		}
		String powerToken = userDetails.getSelfSupportToken();
		String username = userDetails.getUsername();
		Safe safeMetaData = safeUtils.getSafeMetaData(powerToken, safeType, safeName);
		String[] latestPolicies = policyUtils.getCurrentPolicies(powerToken, username);
		ArrayList<String> policiesTobeChecked =  policyUtils.getPoliciesTobeCheked(safeType, safeName);
		boolean isAuthorized = authorizationUtils.isAuthorized(userDetails, safeMetaData, latestPolicies, policiesTobeChecked, true);
		return ResponseEntity.status(HttpStatus.OK).body(String.valueOf(isAuthorized));
	}

	/**
	 * Update a safe by the user with least privileges, Requires an AppRole which can perform Safe updation
	 * (Sufficient access to the paths such as shared or metadata/shared, etc)
	 * @param userToken
	 * @param safe
	 * @return
	 */
	public ResponseEntity<String> updateSafe(UserDetails userDetails, String userToken, Safe safe) {
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.updateSafe(token, safe);
		}
		else {
			token = userDetails.getSelfSupportToken();
			ResponseEntity<String> safe_creation_response = safesService.updateSafe(token, safe);
			return safe_creation_response;
		}
	}
	/**
	 * Delete a safe by the user with least privileges, Requires an AppRole which can perform Safe Deletion
	 * (Sufficient access to the paths such as shared or metadata/shared, etc)
	 * @param userDetails
	 * @param userToken
	 * @param path
	 * @return
	 */
	public ResponseEntity<String> deletefolder(UserDetails userDetails, String userToken, String path) {
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.deletefolder(token, path);
		}
		else {
			token = userDetails.getSelfSupportToken();
			ResponseEntity<String> safe_creation_response = safesService.deletefolder(token, path);
			return safe_creation_response;
		}
	}
	/**
	 * Adds a group to a safe
	 * @param userDetails
	 * @param userToken
	 * @param safeGroup
	 * @return
	 */
	public ResponseEntity<String> addGroupToSafe(UserDetails userDetails, String userToken, SafeGroup safeGroup) {
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.addGroupToSafe(token, safeGroup);
		}
		else {
			ResponseEntity<String> isAuthorized = isAuthorized(userDetails, safeGroup.getPath());
			if (!isAuthorized.getStatusCode().equals(HttpStatus.OK)) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Error checking user permission\"]}");
			}
			if (isAuthorized.getBody().equals("false")) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"errors\":[\"Access denied: no permission to add group to the safe\"]}");
			}
			token = userDetails.getSelfSupportToken();
			return safesService.addGroupToSafe(token, safeGroup);
		}
	}
	/**
	 * Removes a group from safe
	 * @param userDetails
	 * @param userToken
	 * @param safeGroup
	 * @return
	 */
	public ResponseEntity<String> removeGroupFromSafe(UserDetails userDetails, String userToken, SafeGroup safeGroup) {
		String token = userDetails.getClientToken();
		if (userDetails.isAdmin()) {
			return safesService.removeGroupFromSafe(token, safeGroup);
		}
		else {
			ResponseEntity<String> isAuthorized = isAuthorized(userDetails, safeGroup.getPath());
			if (!isAuthorized.getStatusCode().equals(HttpStatus.OK)) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Error checking user permission\"]}");
			}
			if (isAuthorized.getBody().equals("false")) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"errors\":[\"Access denied: no permission to remove group from the safe\"]}");
			}
			token = userDetails.getSelfSupportToken();
			return safesService.removeGroupFromSafe(token, safeGroup);
		}
	}
}
