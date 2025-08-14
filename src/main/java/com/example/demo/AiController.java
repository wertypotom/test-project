package com.example.demo;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Base64;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;

import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.api.OpenAiAudioApi;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/* TTS prompt type (OpenAI speech) */
import org.springframework.ai.openai.audio.speech.SpeechPrompt;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class AiController {

    // ===== existing fields =====
    private final ChatClient chat;
    private final ImageModel imageModel;
    private final MeterRegistry metrics;

    // ===== new audio fields (STT + TTS) =====
    private final OpenAiAudioTranscriptionModel transcriptionModel;
    private final OpenAiAudioSpeechModel speechModel;

    public AiController(
            @Qualifier("plainChatClient") ChatClient chatClient,
            ImageModel imageModel,
            OpenAiAudioTranscriptionModel transcriptionModel,
            OpenAiAudioSpeechModel speechModel,
            MeterRegistry meterRegistry) {
        this.chat = chatClient;
        this.imageModel = imageModel;
        this.transcriptionModel = transcriptionModel;
        this.speechModel = speechModel;
        this.metrics = meterRegistry;
    }

    // ========= 1) Chat =========
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody PromptRequest req) {
        Timer.Sample s = Timer.start(metrics);
        String content = chat.prompt()
                .system("You are a concise, helpful assistant. Reply in under 120 words unless asked otherwise.")
                .user(req.input)
                .call()
                .content();
        s.stop(Timer.builder("ai_latency_seconds").tag("type", "chat").register(metrics));
        metrics.counter("ai_requests_total", "type", "chat").increment();
        metrics.summary("ai_prompt_chars", "type", "chat").record(req.input == null ? 0 : req.input.length());
        return Map.of("answer", content);
    }

    // ========= 2) Summarize =========
    @PostMapping("/summarize")
    public Map<String, Object> summarize(@RequestBody SummarizeRequest req) {
        Timer.Sample s = Timer.start(metrics);
        String prompt = "Summarize the following text in under " + req.maxWords
                + " words, in bullet points when possible:\n\n" + req.text;
        String content = chat.prompt().user(prompt).call().content();
        s.stop(Timer.builder("ai_latency_seconds").tag("type", "summarize").register(metrics));
        metrics.counter("ai_requests_total", "type", "summarize").increment();
        metrics.summary("ai_prompt_chars", "type", "summarize").record(req.text == null ? 0 : req.text.length());
        return Map.of("summary", content);
    }

    // ========= 3) Translate =========
    @PostMapping("/translate")
    public Map<String, Object> translate(@RequestBody TranslateRequest req) {
        Timer.Sample s = Timer.start(metrics);
        String prompt = "Translate to " + (req.to == null ? "English" : req.to)
                + ": " + req.text + "\n\nOnly return the translation.";
        String content = chat.prompt().user(prompt).call().content();
        s.stop(Timer.builder("ai_latency_seconds").tag("type", "translate").register(metrics));
        metrics.counter("ai_requests_total", "type", "translate").increment();
        metrics.summary("ai_prompt_chars", "type", "translate").record(req.text == null ? 0 : req.text.length());
        return Map.of("translation", content);
    }

    // ========= 4) Image generation =========
    @PostMapping(value = "/image", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> image(@RequestBody ImageRequest req) {
        Timer.Sample s = Timer.start(metrics);
        try {
            var resp = imageModel.call(new ImagePrompt(req.prompt));
            var first = resp.getResult().getOutput();
            s.stop(Timer.builder("ai_latency_seconds").tag("type", "image").register(metrics));
            metrics.counter("ai_requests_total", "type", "image").increment();
            metrics.summary("ai_prompt_chars", "type", "image").record(req.prompt == null ? 0 : req.prompt.length());
            String result = (first.getB64Json() != null && !first.getB64Json().isEmpty())
                    ? first.getB64Json()
                    : first.getUrl();
            return Map.of("result", result);
        } catch (Exception ex) {
            s.stop(Timer.builder("ai_latency_seconds").tag("type", "image").register(metrics));
            metrics.counter("ai_errors_total", "type", "image").increment();
            return Map.of(
                    "error", "Image model unavailable (likely org not verified).",
                    "hint", "Verify org in OpenAI settings or disable images for the demo.",
                    "fallbackUrl", "https://picsum.photos/1024"
            );
        }
    }

    // ========= 5a) Audio — Speech-to-Text (multipart upload) =========
    // POST /api/audio/transcribe (multipart/form-data: file=<audio>, language=optional)
    @PostMapping(
            path = "/audio/transcribe",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String language) throws Exception {

        Timer.Sample s = Timer.start(metrics);

        // Give Resource a filename (OpenAI expects a name)
        Resource audio = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };

        // Use the builder names from Spring AI 1.0.1: language(), responseFormat(), …
        var options = OpenAiAudioTranscriptionOptions.builder()
                // .model("gpt-4o-transcribe") // or "whisper-1" (leave to YAML unless overriding)
                .language(language)
                .responseFormat(OpenAiAudioApi.TranscriptResponseFormat.TEXT)
                .build();

        AudioTranscriptionResponse resp =
                transcriptionModel.call(new AudioTranscriptionPrompt(audio, options));

        String text = resp.getResult().getOutput();

        s.stop(Timer.builder("ai_latency_seconds").tag("type", "stt").register(metrics));
        metrics.counter("ai_requests_total", "type", "stt").increment();
        metrics.summary("ai_prompt_chars", "type", "stt").record(file.getSize());

        return Map.of("transcript", text);
    }

    // ========= 5b) Audio — Text-to-Speech =========
    // POST /api/audio/tts  { "text": "Hello", "voice": "ALLOY" }  -> { audioB64, mime }
    public static record TtsRequest(String text, String voice) {}

    @PostMapping(path = "/audio/tts", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> tts(@RequestBody TtsRequest req) {
        Timer.Sample s = Timer.start(metrics);

        var opts = OpenAiAudioSpeechOptions.builder()
                // .model("gpt-4o-mini-tts") // let YAML set default unless you need to override
                .voice(req.voice() == null
                        ? OpenAiAudioApi.SpeechRequest.Voice.ALLOY
                        : OpenAiAudioApi.SpeechRequest.Voice.valueOf(req.voice().toUpperCase()))
                .responseFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3)
                .build();

        byte[] audioBytes = speechModel
                .call(new SpeechPrompt(req.text(), opts))
                .getResult()
                .getOutput();

        s.stop(Timer.builder("ai_latency_seconds").tag("type", "tts").register(metrics));
        metrics.counter("ai_requests_total", "type", "tts").increment();
        metrics.summary("ai_prompt_chars", "type", "tts").record(
                req.text() == null ? 0 : req.text().length());

        String b64 = Base64.getEncoder().encodeToString(audioBytes);
        return Map.of("audioB64", b64, "mime", "audio/mpeg");
    }
}
