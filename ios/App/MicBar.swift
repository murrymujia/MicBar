import SwiftUI
import AVFoundation
import Speech

@main
struct MicBarApp: App {
    var body: some Scene { WindowGroup { DictationView() } }
}

final class Dictation: ObservableObject {
    enum Phase { case idle, requesting, recording, finishing }
    @Published var phase = Phase.idle
    @Published var draft = ""
    @Published var status = ""
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?
    private var tapped = false
    private var session = UUID()

    func begin(locale: String) {
        guard phase == .idle else { return }
        let id = UUID()
        session = id
        phase = .requesting
        status = ""
        SFSpeechRecognizer.requestAuthorization { permission in
            DispatchQueue.main.async {
                guard self.session == id else { return }
                guard permission == .authorized else { self.reset("请在系统设置允许语音识别。"); return }
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        guard self.session == id else { return }
                        guard granted else { self.reset("请在系统设置允许麦克风。"); return }
                        self.record(locale: locale, id: id)
                    }
                }
            }
        }
    }

    private func record(locale: String, id: UUID) {
        guard let service = SFSpeechRecognizer(locale: Locale(identifier: locale)),
              service.isAvailable, service.supportsOnDeviceRecognition else {
            reset("当前语言的本机识别暂不可用，请检查系统听写设置。")
            return
        }
        do {
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.record, mode: .measurement)
            try audio.setActive(true)
            let node = engine.inputNode
            let format = node.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else { reset("没有可用的麦克风。"); return }
            let req = SFSpeechAudioBufferRecognitionRequest()
            req.requiresOnDeviceRecognition = true
            req.shouldReportPartialResults = true
            req.addsPunctuation = true
            req.contextualStrings = ["Python", "Codex", "GitHub", "Docker"]
            request = req
            recognizer = service
            let original = draft
            node.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in req.append(buffer) }
            tapped = true
            task = service.recognitionTask(with: req) { result, error in
                DispatchQueue.main.async {
                    guard self.session == id else { return }
                    if let result = result {
                        let addition = result.bestTranscription.formattedString
                        self.draft = [original, addition].filter { !$0.isEmpty }.joined(separator: " ")
                        if result.isFinal { self.reset(""); return }
                    }
                    if error != nil { self.reset("识别已停止，已有草稿可以编辑。") }
                }
            }
            engine.prepare()
            try engine.start()
            phase = .recording
        } catch { reset("录音启动失败，请检查音频设备。") }
    }

    func stop() {
        guard phase == .recording else { return }
        phase = .finishing
        stopAudio()
        request?.endAudio()
        let id = session
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            if self.session == id && self.phase == .finishing { self.reset("已保留当前草稿。") }
        }
    }
    private func stopAudio() {
        if engine.isRunning { engine.stop() }
        if tapped { engine.inputNode.removeTap(onBus: 0); tapped = false }
    }
    func reset(_ message: String) {
        session = UUID()
        stopAudio()
        task?.cancel()
        task = nil
        request = nil
        recognizer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        phase = .idle
        status = message
    }
}

struct DictationView: View {
    @StateObject private var voice = Dictation()
    @State private var locale = "zh-CN"
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        NavigationStack {
            Form {
                Section("本机听写") {
                    Picker("识别语言", selection: $locale) {
                        Text("普通话").tag("zh-CN")
                        Text("English").tag("en-US")
                    }.disabled(voice.phase != .idle)
                    Button {
                        if voice.phase == .recording { voice.stop() } else { voice.begin(locale: locale) }
                    } label: {
                        Label(voice.phase == .recording ? "结束听写" : "开始听写", systemImage: voice.phase == .recording ? "stop.circle.fill" : "mic.fill")
                    }.disabled(voice.phase == .requesting || voice.phase == .finishing)
                    if !voice.status.isEmpty { Text(voice.status).font(.footnote) }
                }
                Section("草稿") {
                    TextEditor(text: $voice.draft).frame(minHeight: 96).disabled(voice.phase != .idle)
                        .accessibilityLabel("听写草稿")
                    Button("复制并清空草稿") {
                        UIPasteboard.general.setItems([["public.utf8-plain-text": voice.draft]],
                            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(300)])
                        voice.draft = ""
                        voice.status = "已复制，五分钟内可在远控输入框粘贴。"
                    }.disabled(voice.draft.isEmpty || voice.phase != .idle)
                }
                Section("启用键盘") {
                    Text("在「设置 → 通用 → 键盘 → 键盘 → 添加新键盘」中选择轻声键盘。在输入框长按地球键切换。")
                    Text("使用远控时，可在本 App 听写并复制，再回到输入框粘贴。iPad 支持分屏的应用可并排使用。")
                }
            }.navigationTitle("轻声键盘")
        }.onChange(of: scenePhase) { phase in
            if phase == .background && voice.phase != .idle { voice.reset("已结束听写。") }
        }
    }
}
