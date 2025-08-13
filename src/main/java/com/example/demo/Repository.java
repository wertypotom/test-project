package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

@org.springframework.stereotype.Repository
public class Repository {
    private String name;

    private void say(String message) {}

    public void sayHello() {}

    public void sayHello(String name) {}

    public List<String> getFriends() {
        return new ArrayList<>();
    }
}
