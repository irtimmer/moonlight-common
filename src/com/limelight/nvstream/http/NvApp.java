package com.limelight.nvstream.http;

import com.limelight.LimeLog;

public class NvApp {
	private String appName = "";
	private int appId;
	private boolean isRunning;
	private boolean initialized;
	
	public NvApp() {}
	
	public NvApp(String appName) {
		this.appName = appName;
	}
	
	public NvApp(String appName, int appId) {
		this.appName = appName;
		this.appId = appId;
		this.initialized = true;
	}
	
	public void setAppName(String appName) {
		this.appName = appName;
	}
	
	public void setAppId(String appId) {
		try {
			this.appId = Integer.parseInt(appId);
			this.initialized = true;
		} catch (NumberFormatException e) {
			LimeLog.warning("Malformed app ID: "+appId);
		}
	}
	
	public void setAppId(int appId) {
		this.appId = appId;
		this.initialized = true;
	}
	
	public void setIsRunning(String isRunning) {
		this.isRunning = isRunning.equals("1");
	}
	
	public void setIsRunning(boolean isRunning) {
		this.isRunning = isRunning;
	}
	
	public String getAppName() {
		return this.appName;
	}
	
	public int getAppId() {
		return this.appId;
	}
	
	public boolean getIsRunning() {
		return this.isRunning;
	}
	
	public boolean isInitialized() {
		return this.initialized;
	}
}
