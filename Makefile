# Agent on a Leash — wallet control layer. Run `make` to list targets.

-include .env
export

BACKEND_PORT  ?= 8080
FRONTEND_PORT ?= 3000
s ?=

# make provision_local: a model served by the Ollama container (compose profile "local") instead of the OpenAI API.
# Ollama speaks the OpenAI API, so the backend only gets another base URL, a dummy key, the model name and longer timeouts.
LOCAL_MODEL ?= qwen2.5:7b
LOCAL_ENV = OPENAI_BASE_URL=http://ollama:11434/v1 OPENAI_API_KEY=ollama OPENAI_MODEL=$(LOCAL_MODEL) COMPILE_TIMEOUT_MS=180000 JUDGE_TIMEOUT_MS=90000

# The UI (compose profile "ui") is included automatically once frontend/Dockerfile exists.
DC = docker compose $(if $(wildcard frontend/Dockerfile),--profile ui)

.DEFAULT_GOAL := help
.PHONY: help env build provision provision_local deprovision restart logs status example-policies check-cases clean

help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage: make \033[36m<target>\033[0m\n\n"} /^[a-zA-Z_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo

env: ## Create .env from .env.example if missing
	@test -f .env || (cp .env.example .env && echo "Created .env - add your keys")

build: env ## Build the Docker images (backend, plus the UI once it exists) without starting them
	$(DC) build

provision: env ## Build and start the system (backend, plus the UI once it exists)
	docker compose up -d --wait
	@echo ""
	@echo "  API  http://localhost:$(BACKEND_PORT)/status"
	@echo "  Docs http://localhost:$(BACKEND_PORT)/swagger-ui.html"
	@test ! -f frontend/Dockerfile || echo "  UI   http://localhost:$(FRONTEND_PORT)"

provision_local: env ## Like provision, but a local model in a container (Ollama) replaces the OpenAI API: make provision_local LOCAL_MODEL=qwen2.5:14b
	docker compose --profile local up -d --wait ollama
	docker compose --profile local exec ollama ollama pull $(LOCAL_MODEL)
	$(LOCAL_ENV) docker compose --profile local up -d --build --wait backend
	@echo ""
	@echo "  Model $(LOCAL_MODEL), served by the leash-ollama container (no OpenAI calls)"
	@echo "  API  http://localhost:$(BACKEND_PORT)/status"
	@echo "  Docs http://localhost:$(BACKEND_PORT)/swagger-ui.html"
	@echo "  Note: make restart s=backend goes back to the OpenAI settings of .env; run make provision_local again to restart in local mode"

deprovision: ## Stop the system and delete its volumes (saved policies and downloaded local models included)
	docker compose --profile local down -v

restart: ## Rebuild and restart one service: make restart s=backend
	docker compose up -d --build --no-deps $(s)

logs: ## Tail logs (all, or one service: make logs s=backend)
	docker compose logs -f --tail=100 $(s)

status: ## Backend status: keys configured, worker running, counters
	@curl -fsS http://localhost:$(BACKEND_PORT)/status | jq && echo

example-policies: ## Create the 15 example policies from plans/EXAMPLE_POLICY_CURLS.md (skips ones that exist)
	@./scripts/create-example-policies.sh

check-cases: ## Send 100 purchases to /check against the 15 example policies, with live progress and pass/fail
	@./scripts/run-check-cases.sh

clean: ## Remove local build outputs
	rm -rf backend/target frontend/.next
