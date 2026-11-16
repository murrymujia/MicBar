import UIKit

final class KeyboardController: UIInputViewController {
    private let rows = UIStackView()
    private var spelling = ""
    private var chinese = true, shifted = false, symbols = false, folded = false
    private var height: NSLayoutConstraint?
    private var narrowHeight = false
    private var lastDocument: UUID?
    private lazy var dictionary: [String: [String]] = {
        var entries: [String: [String]] = [:]
        for name in ["characters", "pinyin-common"] {
            guard let url = Bundle(for: KeyboardController.self).url(forResource: name, withExtension: "tsv"),
                  let content = try? String(contentsOf: url, encoding: .utf8) else { continue }
            for row in content.split(separator: "\n") {
                let fields = row.split(separator: "\t")
                guard fields.count == 2 else { continue }
                entries[String(fields[0]), default: []].append(String(fields[1]))
            }
        }
        return entries
    }()
    override func viewDidLoad() {
        super.viewDidLoad()
        rows.axis = .vertical
        rows.spacing = 4
        rows.translatesAutoresizingMaskIntoConstraints = false
        view.backgroundColor = .systemGroupedBackground
        view.addSubview(rows)
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([rows.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 4),
            rows.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -4),
            rows.topAnchor.constraint(equalTo: view.topAnchor, constant: 3),
            rows.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -3)])
        height = view.heightAnchor.constraint(equalToConstant: 216)
        height?.priority = UILayoutPriority(999)
        height?.isActive = true
        redraw()
    }
    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        let compact = traitCollection.verticalSizeClass == .compact
        if compact != narrowHeight { narrowHeight = compact; redraw() }
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        spelling = ""; shifted = false
        redraw()
    }
    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        let document = textDocumentProxy.documentIdentifier
        if lastDocument != document {
            lastDocument = document
            spelling = ""; shifted = false
            if isViewLoaded { redraw() }
        }
    }
    private func row() -> UIStackView {
        let line = UIStackView()
        line.axis = .horizontal
        line.spacing = 3
        line.distribution = .fillEqually
        rows.addArrangedSubview(line)
        return line
    }
    @discardableResult private func key(_ title: String, _ line: UIStackView, _ action: @escaping () -> Void) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.titleLabel?.adjustsFontSizeToFitWidth = true
        b.titleLabel?.minimumScaleFactor = 0.65
        b.titleLabel?.font = .systemFont(ofSize: narrowHeight ? 15 : 18)
        b.backgroundColor = .secondarySystemGroupedBackground
        b.layer.cornerRadius = 6
        b.addAction(UIAction { _ in action() }, for: .touchUpInside)
        line.addArrangedSubview(b)
        return b
    }
    private func commit(_ word: String) {
        spelling = ""
        if !word.isEmpty { textDocumentProxy.insertText(word) }
    }
    private func backspace() {
        if spelling.isEmpty { textDocumentProxy.deleteBackward() } else { spelling.removeLast() }
        redraw()
    }
    private func redraw() {
        rows.arrangedSubviews.forEach { $0.removeFromSuperview() }
        rows.distribution = .fillEqually
        height?.constant = folded ? 42 : narrowHeight ? 164 : 220
        let tools = row()
        if spelling.isEmpty {
            key(folded ? "展开键盘" : "收起按键", tools) { self.folded.toggle(); self.redraw() }
            key("退格", tools) { self.backspace() }
            key("回车", tools) { self.textDocumentProxy.insertText("\n") }
            key("隐藏", tools) { self.dismissKeyboard() }
        } else {
            let scroll = UIScrollView()
            let items = UIStackView()
            items.spacing = 5
            items.translatesAutoresizingMaskIntoConstraints = false
            scroll.addSubview(items)
            NSLayoutConstraint.activate([items.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
                items.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
                items.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
                items.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
                items.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)])
            tools.addArrangedSubview(scroll)
            for word in [spelling] + (dictionary[spelling] ?? []) {
                key(word, items) { self.commit(word); self.redraw() }.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            }
        }
        if folded { return }
        let layout = symbols ? ["1234567890","@#$%&*-+/","()，。？！"] : ["qwertyuiop","asdfghjkl","zxcvbnm"]
        for (i,letters) in layout.enumerated() {
            let line = row()
            if i == 2 && !symbols {
                key("⇧", line) { self.shifted.toggle(); self.redraw() }.accessibilityLabel = "大写"
            }
            for c in letters {
                let symbol = shifted && !symbols ? String(c).uppercased() : String(c)
                key(symbol, line) {
                    if self.chinese && !self.symbols && !self.shifted { self.spelling.append(c) }
                    else { self.commit(self.spelling + symbol) }
                    self.shifted = false
                    self.redraw()
                }
            }
            if i == 2 { key("⌫", line) { self.backspace() }.accessibilityLabel = "退格" }
        }
        let bottom = row()
        let globe = UIButton(type: .system)
        globe.setImage(UIImage(systemName: "globe"), for: .normal)
        globe.accessibilityLabel = "切换输入法"
        globe.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
        bottom.addArrangedSubview(globe)
        key(symbols ? "ABC" : "123", bottom) { self.commit(self.spelling); self.symbols.toggle(); self.redraw() }
        key(chinese ? "中 / 英" : "EN / 中", bottom) { self.commit(self.spelling); self.chinese.toggle(); self.redraw() }
        key("空格", bottom) {
            self.commit(self.spelling.isEmpty ? " " : self.dictionary[self.spelling]?.first ?? self.spelling)
            self.redraw()
        }
        key("↵", bottom) {
            if self.spelling.isEmpty { self.textDocumentProxy.insertText("\n") }
            else { self.commit(self.dictionary[self.spelling]?.first ?? self.spelling) }
            self.redraw()
        }.accessibilityLabel = "确认或回车"
    }
}
