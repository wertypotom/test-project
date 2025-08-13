# 🚀 AI Commit & PR Description Automation (Spring Boot)

Two developer-productivity features bundled into this Spring Boot app:

- **AI-Powered Commit Message Generator** – drafts clean, Conventional Commit–style messages from your staged changes.
- **PR Description Generator** – builds structured PR summaries from commit history (optionally AI-polished).

---

## ✨ Features

### 1) AI Commit Message Generator
- **What it is:** A local HTTP service + optional git hook that turns your staged diff into a conventional commit message.
- **What it does:**
    - Analyzes `git diff --staged` and changed files
    - Generates `type(scope): subject` with an optional wrapped body
    - Infers scope from file paths; respects breaking changes/issue refs if present
- **Why it’s useful:** Consistent, high-quality commit history with less manual typing.

### 2) PR Description Generator
- **What it is:** A service that parses your commits and creates a neat Markdown PR description.
- **What it does:**
    - Groups commits by type (`feat`, `fix`, `chore`, …)
    - Adds a title, summary, typed change list, breaking changes, and related issues
- **Why it’s useful:** Faster reviewing and clearer PRs out of the box.

---

## 📦 Requirements

- **Java 21+**
- **Maven 3.9+**
- **Git**
- **OpenAI API key** *(only required for AI-powered pieces — deterministic PR generator works without it)*

> **Environment variable:**  
> The app reads your OpenAI key from `SPRING_AI_OPENAI_API_KEY`.

---

## 🔧 Setup

### 1) Set your OpenAI API key (local)
```
# One-off for the current shell:
export SPRING_AI_OPENAI_API_KEY=sk-...
```
You can also setup the key using project Environmental variables

### 2) Build & Run the Service Locally
```
# Clone your repo
git clone https://github.com/your-org/your-repo.git
cd your-repo

# Run your project using IDE of your choice
```

## 📝 How to Use the Commit Feature

```
git checkout -b new_branch
git add path/to/changed/files
git commit
```

If API key works fine you will see AI generated commit based on code changes

## 🔄 How to Use the PR Description Feature
### Step 1 – Push your branch to GitHub
```
git push origin new_branch
```

### Step 2 – Create PR
On GitHub, open a Pull Request from your branch and wait until all pipelines will finish

