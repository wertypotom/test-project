package com.example.demo;

public class PromptRequest { public String input; }
class TranslateRequest { public String text; public String to; }
class SummarizeRequest { public String text; public int maxWords = 80; }
class ImageRequest { public String prompt; public int n = 1; public String size = "1024x1024"; }
