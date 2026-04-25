# TestPilot — Java

A complete Java port of the **ExecutionAgent**: an autonomous AI agent that sets up, builds, and runs test suites for software projects inside Docker containers. It analyzes project requirements, generates Dockerfiles, and executes build/test commands using an LLM.

> Functionally identical to the Python version. Same tools, same prompts, same retry logic, same output structure.

---

## Prerequisites

| Requirement | Minimum Version | Check |
|---|---|---|
| Java (JDK) | 17+ | `java -version` |
| Gradle | 7+ | `gradle --version` |
| Docker | 20+ | `docker --version` |
| Git | Any | `git --version` |
| OpenAI-compatible API key | — | — |

### macOS (Homebrew) quick install

```bash
brew install openjdk gradle
brew install --cask docker        # Docker Desktop
```

---

## Build

```bash
# Clone / enter the project
cd ExecutionAgent-Java

# Build the fat JAR (all dependencies bundled)
JAVA_HOME=/opt/homebrew/opt/openjdk gradle shadowJar
```

Output: `build/libs/execution-agent-1.0.0.jar`

---

## Quick Start

### 1. Set your API key

```bash
export OPENAI_API_KEY="sk-...your-key..."
```

### 2. Create a project metadata file

```bash
cat > project_meta_data.json << 'EOF'
{
  "project_path": "my_project",
  "project_url": "https://github.com/username/my_project",
  "language": "python",
  "budget": 40
}
EOF
```

### 3. Run the agent

```bash
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json
```

That's it. The agent will:
1. Clone the repository locally
2. Search the web and read CI/CD files for context
3. Ask the LLM to write a Dockerfile and run commands
4. Retry up to 2 times if budget is exhausted, carrying lessons forward
5. Save all artifacts and logs under `execution_agent_workspace/`

---

## Command-Line Options

```
Usage: execution-agent [OPTIONS]

Options:
  --experiment-file   Path to project_meta_data.json        (required)
  --task              Custom task string
  --task-file         File containing the task instructions
  --model             LLM model name                        (default: gpt-4o-mini)
  --knowledge-model   Model for web search & summaries      (default: gpt-4o-mini)
  --api-key           OpenAI API key (or use $OPENAI_API_KEY)
  --base-url          Base URL for OpenAI-compatible API    (default: https://api.openai.com/v1)
  --workspace-root    Directory for clones and outputs      (default: execution_agent_workspace)
  --prompt-files      Path to prompt template directory     (default: src/execution_agent/prompt_files)
  --run-log-dir       Custom directory for this run's logs
  --max-retries       Retries after budget exhaustion       (default: 2)
  --log-level         DEBUG | INFO | WARNING | ERROR        (default: INFO)
  -h, --help          Show this help message
  -V, --version       Print version
```

### Examples

```bash
# Use a specific model
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json \
  --model gpt-4o \
  --knowledge-model gpt-4o

# Point to a custom OpenAI-compatible endpoint (e.g. Azure, local Ollama)
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json \
  --base-url https://my-azure-endpoint.openai.azure.com/openai/deployments/gpt-4o \
  --model gpt-4o

# Custom workspace and more retries
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json \
  --workspace-root /tmp/agent_runs \
  --max-retries 4

# Give the agent a specific task instead of the default
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json \
  --task "Set up the project and run only unit tests, not integration tests."

# Keep logs in a fixed path (useful for CI)
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json \
  --run-log-dir /tmp/my_run_logs
```

---

## Project Metadata Format (`project_meta_data.json`)

```json
{
  "project_path": "scipy",
  "project_url": "https://github.com/scipy/scipy",
  "language": "python",
  "budget": 40
}
```

| Field | Description | Required |
|---|---|---|
| `project_path` | Local directory name for the cloned repo | Yes |
| `project_url` | Git repository URL | Yes |
| `language` | Primary language: `python`, `java`, `javascript`, `c`, `c++`, `rust` | Yes |
| `budget` | Maximum agent cycles (steps) per attempt | No (default: 40) |

---

## Output Structure

All outputs land under `execution_agent_workspace/` (or `--workspace-root`):

```
execution_agent_workspace/
├── <project_name>/                     # Cloned repository
└── _run_logs/
    └── <project_name>/
        └── <YYYYMMDD_HHmmss>/          # One directory per run
            ├── messages_transcript.txt     # Human-readable conversation log
            ├── messages_transcript.jsonl   # JSONL conversation log
            ├── messages.json               # Full conversation history
            ├── replay_trace.sh             # Bash script to replay the run
            ├── tool_metrics.json           # Per-tool call statistics
            ├── agent_state.json            # State snapshot for crash recovery
            ├── success_artifacts/          # Created on success
            │   ├── Dockerfile              # The working Dockerfile
            │   ├── commands.sh             # All commands run inside the container
            │   ├── launch.sh               # Self-contained rebuild + rerun script
            │   └── manifest.json           # Run metadata
            └── forced_exit_cycle/          # Created if all retries exhausted
                ├── Dockerfile
                ├── run_tests.sh
                ├── test_output.log
                └── docker_build.log
```

### Key output files

| File | Purpose |
|---|---|
| `messages_transcript.txt` | Every LLM message in readable form — start here to understand what happened |
| `replay_trace.sh` | Re-runs all docker commands and terminal commands from the run |
| `success_artifacts/launch.sh` | Single script that rebuilds the image and reruns tests — share this to reproduce the result |
| `tool_metrics.json` | JSON with call count, success rate, and avg duration per tool |

### Reproducing a successful run

```bash
cd execution_agent_workspace/_run_logs/<project>/<timestamp>/success_artifacts/
bash launch.sh
```

### Monitoring a live run

```bash
# In a second terminal, tail the transcript
tail -f execution_agent_workspace/_run_logs/<project>/<timestamp>/messages_transcript.txt
```

---

## Available Tools

The agent uses exactly five tools:

| Tool | Description |
|---|---|
| `linux_terminal` | Run bash commands (local before container; inside container via GNU screen after) |
| `read_file` | Read a file's contents (local or container) |
| `write_to_file` | Write a file; writing a `Dockerfile` triggers image build and container start |
| `search_docker_image` | Search Docker Hub for base images |
| `goals_accomplished` | Signal that tests passed; terminates the run successfully |

---

## Retry Mechanism

```
Attempt 1  ──► budget exhausted ──► generate attempt summary
Attempt 2  ──► budget exhausted ──► generate attempt summary   } up to --max-retries
Attempt 3  ──► budget exhausted ─┐
                                  └──► Forced Exit Cycle
                                       (knowledge model writes final Dockerfile + script
                                        and runs it directly via docker CLI)
```

Each retry starts fresh but carries all previous attempt summaries in the prompt so the LLM knows exactly what failed and why.

---

## Language-Specific Guidelines

The agent automatically loads guidelines for the project's language from `src/main/resources/prompt_files/`:

| Language value | Loaded file |
|---|---|
| `python` / `py` | `python_guidelines` |
| `java` | `java_guidelines` |
| `javascript` / `js` / `typescript` / `ts` | `javascript_guidelines` |
| `c` | `c_guidelines` |
| `c++` / `cpp` | `cpp_guidelines` |
| `rust` / `rs` | `rust_guidelines` |

---

## Using a Non-OpenAI Provider

The agent calls any OpenAI-compatible REST API. Pass `--base-url` to redirect:

```bash
# Azure OpenAI
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json \
  --base-url "https://<resource>.openai.azure.com/openai/deployments/<deployment>" \
  --model gpt-4o

# Local model via Ollama
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json \
  --base-url "http://localhost:11434/v1" \
  --model llama3

# Anthropic Claude via a proxy
java -jar build/libs/execution-agent-1.0.0.jar \
  --experiment-file project_meta_data.json \
  --base-url "https://api.anthropic.com/v1" \
  --model claude-opus-4-7
```

---

## Wrapper Scripts

### `run_agent.sh` — basic wrapper

```bash
#!/usr/bin/env bash
set -euo pipefail

export OPENAI_API_KEY="${OPENAI_API_KEY:-sk-your-key-here}"
JAR="$(dirname "$0")/build/libs/execution-agent-1.0.0.jar"

if [ $# -lt 1 ]; then
  echo "Usage: $0 <metadata.json> [extra args...]"
  exit 1
fi

JAVA_HOME=/opt/homebrew/opt/openjdk \
  java -jar "$JAR" \
    --experiment-file "$1" \
    --model "${MODEL:-gpt-4o-mini}" \
    --knowledge-model "${KNOWLEDGE_MODEL:-gpt-4o-mini}" \
    --max-retries "${MAX_RETRIES:-2}" \
    "${@:2}"
```

```bash
chmod +x run_agent.sh
./run_agent.sh project_meta_data.json
MODEL=gpt-4o ./run_agent.sh project_meta_data.json
```

### `run_batch.sh` — run all JSON files in a directory

```bash
#!/usr/bin/env bash
set -euo pipefail

export OPENAI_API_KEY="${OPENAI_API_KEY:-sk-your-key-here}"
METADATA_DIR="${1:-./metadata}"
JAR="$(dirname "$0")/build/libs/execution-agent-1.0.0.jar"
LOG="batch_$(date +%Y%m%d_%H%M%S).log"

for meta in "$METADATA_DIR"/*.json; do
  echo "========== $(basename "$meta") ==========" | tee -a "$LOG"
  JAVA_HOME=/opt/homebrew/opt/openjdk java -jar "$JAR" \
    --experiment-file "$meta" \
    --model gpt-4o-mini \
    --max-retries 2 \
    2>&1 | tee -a "$LOG"
done
echo "Done. Log: $LOG"
```

---

## Environment Variables

| Variable | Description |
|---|---|
| `OPENAI_API_KEY` | API key (required if `--api-key` not passed) |
| `OPENAI_MODEL` | Default model name (overridden by `--model`) |
| `KNOWLEDGE_MODEL` | Default knowledge model (overridden by `--knowledge-model`) |
| `OPENAI_BASE_URL` | Default API base URL (overridden by `--base-url`) |
| `EXECUTION_AGENT_SCREEN_SESSION` | GNU screen session name inside container (default: `exec_agent_screen`) |
| `EXECUTION_AGENT_RUN_DIR` | Temp dir for screen logs inside container (default: `/tmp/screen_runs`) |
| `EXECUTION_AGENT_STUCK_TIMEOUT` | Seconds before a command is considered stuck (default: `300`) |
| `EXECUTION_AGENT_POLL_INTERVAL` | Polling interval in seconds (default: `0.5`) |
| `EXECUTION_AGENT_MAX_TAIL_BYTES` | Max bytes read from container log files (default: `2000000`) |
| `EXECUTION_AGENT_EXEC_TIMEOUT` | Timeout for individual container exec calls (default: `60`) |

---

## Debugging

**Start here:**
```bash
# Watch the conversation as it happens
tail -f execution_agent_workspace/_run_logs/<project>/<timestamp>/messages_transcript.txt

# Check what cycle the agent is on
grep "CYCLE" execution_agent_workspace/_run_logs/<project>/<timestamp>/messages_transcript.txt | tail -5
```

**If the agent gets stuck on a command:**  
The `linux_terminal` tool detects commands with no output change for `STUCK_TIMEOUT_SECONDS` (default 300s) and flags them. The LLM then responds with `WAIT`, `TERMINATE`, or `WRITE:<input>` to unblock.

**If a Docker build fails:**  
The build log is printed inline. Check the `messages_transcript.txt` for the exact Dockerfile that was attempted.

**To replay a run manually:**
```bash
bash execution_agent_workspace/_run_logs/<project>/<timestamp>/replay_trace.sh
```

---

## Project Structure

```
ExecutionAgent-Java/
├── build.gradle.kts                    # Gradle build (primary)
├── pom.xml                             # Maven build (alternative)
├── settings.gradle.kts
├── project_meta_data.json              # Sample metadata
└── src/
    └── main/
        ├── java/com/executionagent/
        │   ├── Main.java               # Entry point + CLI + retry loop
        │   ├── agent/
        │   │   └── ExecutionAgent.java # Core plan→execute→summarize loop
        │   ├── tools/
        │   │   ├── Tools.java          # 5 tool implementations
        │   │   └── ToolRegistry.java   # Tool registration + dispatch
        │   ├── context/
        │   │   ├── ContextBuilder.java # Repo clone, web search, CI/CD analysis
        │   │   └── RepoContext.java    # Context data object
        │   ├── models/
        │   │   ├── LlmModel.java       # Interface
        │   │   └── LitellmModel.java   # OpenAI-compatible HTTP client
        │   ├── utils/
        │   │   ├── SharedUtils.java    # Docker exec, text processing, constants
        │   │   ├── DockerHelpers.java  # Image build, container lifecycle, screen exec
        │   │   ├── MetricsCollector.java
        │   │   └── ToolMetrics.java
        │   ├── env/
        │   │   └── ExecutionEnvironment.java
        │   ├── exceptions/             # GoalsAccomplished, FormatError, BudgetExhausted
        │   ├── repetition/
        │   │   └── RepetitionDetector.java  # AAAAAA / ABABAB / ABCABC / AAABBB
        │   ├── persistence/
        │   │   ├── AgentState.java
        │   │   └── StatePersistence.java    # JSON state snapshots for crash recovery
        │   ├── artifacts/
        │   │   └── ExitArtifacts.java       # success_artifacts/ generation
        │   ├── trace/
        │   │   └── TraceToBash.java         # replay_trace.sh generation
        │   └── logging/
        │       └── TranscriptWriters.java   # .txt / .jsonl / .json transcripts
        └── resources/
            ├── logback.xml
            └── prompt_files/               # 14 prompt templates (copied from Python)
                ├── cycle_instruction
                ├── summarize_cycle
                ├── tools_list
                ├── python_guidelines
                ├── java_guidelines
                └── ...
```
