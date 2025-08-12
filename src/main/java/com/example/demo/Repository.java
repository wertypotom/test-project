package com.example.demo;

import java.util.ArrayList;
import java.util.List;

@org.springframework.stereotype.Repository
public class Repository {
    public void sayHello() {}

    public void sayHello(String name) {}

    public List<String> getFriends() {
        return new ArrayList<>();
    }
}
