package com.example.demo;

import java.util.ArrayList;
import java.util.List;

public class SomeDTO {
    private SomeDTO() {}


    private String name = "SomeDTO";
    private String message = "Some message";
    private Integer age = 20;
    private List<String> friends = new ArrayList<>();
    private String animal = "Animal";
    private String mother = "Mother";

    public String getAnimal() {
        return animal;
    }

    public void setAnimal(String animal) {
        this.animal = animal;
    }

    public String getMother() {
        return mother;
    }

    public void setMother(String mother) {
        this.mother = mother;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getFriends() {
        return friends;
    }

    public void setFriends(List<String> friends) {
        this.friends = friends;
    }
}
