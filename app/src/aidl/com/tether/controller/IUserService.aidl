// IUserService.aidl
package com.tether.controller;

interface IUserService {
    String executeCommand(String command);
    void ping();
}