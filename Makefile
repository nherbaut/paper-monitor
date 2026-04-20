SHELL := /bin/bash

DOCKER ?= docker
DOCKERHUB_NAMESPACE ?= nherbaut
IMAGE_TAG ?= latest

DOCKERFILE_IMAGE_DIRS := $(sort $(dir $(wildcard */Dockerfile) $(wildcard */Containerfile)))
DOCKERFILE_IMAGES := $(patsubst %/,%,$(DOCKERFILE_IMAGE_DIRS))
QUARKUS_IMAGES := $(if $(wildcard app/pom.xml),app,)
IMAGES := $(sort $(DOCKERFILE_IMAGES) $(QUARKUS_IMAGES))

.PHONY: help list build push build-java push-java build-app push-app build-% push-%

help:
	@echo "Targets:"
	@echo "  make list                      List image-producing subfolders"
	@echo "  make build                     Build every image found in subfolders"
	@echo "  make push                      Push every image found in subfolders to Docker Hub"
	@echo "  make build-java                Build only the Quarkus app image"
	@echo "  make push-java                 Push only the Quarkus app image"
	@echo "Notes:"
	@echo "  app/ is built with the Quarkus container-image plugin"
	@echo "  other subfolders use Dockerfile/Containerfile builds"
	@echo "Variables:"
	@echo "  DOCKERHUB_NAMESPACE=<user>     Required for build/push image naming"
	@echo "  IMAGE_TAG=<tag>                Defaults to latest"
	@echo "Examples:"
	@echo "  make list"
	@echo "  make build-java DOCKERHUB_NAMESPACE=mydockerhubuser IMAGE_TAG=dev"
	@echo "  make build DOCKERHUB_NAMESPACE=mydockerhubuser IMAGE_TAG=dev"
	@echo "  make push-java DOCKERHUB_NAMESPACE=mydockerhubuser IMAGE_TAG=latest"
	@echo "  make push DOCKERHUB_NAMESPACE=mydockerhubuser IMAGE_TAG=latest"

list:
	@printf '%s\n' $(IMAGES)

build: $(addprefix build-,$(IMAGES))

push: $(addprefix push-,$(IMAGES))

build-java: build-app

push-java: push-app

build-app:
	@if [[ -z "$(DOCKERHUB_NAMESPACE)" ]]; then \
		echo "DOCKERHUB_NAMESPACE is required"; \
		exit 1; \
	fi
	cd app && ./mvnw package \
		-Dquarkus.container-image.group=$(DOCKERHUB_NAMESPACE) \
		-Dquarkus.container-image.name=paper-monitor-app \
		-Dquarkus.container-image.tag=$(IMAGE_TAG) \
		-Dquarkus.container-image.build=true \
		-Dquarkus.container-image.push=false

push-app:
	@if [[ -z "$(DOCKERHUB_NAMESPACE)" ]]; then \
		echo "DOCKERHUB_NAMESPACE is required"; \
		exit 1; \
	fi
	cd app && ./mvnw package \
		-Dquarkus.container-image.group=$(DOCKERHUB_NAMESPACE) \
		-Dquarkus.container-image.name=paper-monitor-app \
		-Dquarkus.container-image.tag=$(IMAGE_TAG) \
		-Dquarkus.container-image.build=true \
		-Dquarkus.container-image.push=true

build-%:
	@if [[ -z "$(DOCKERHUB_NAMESPACE)" ]]; then \
		echo "DOCKERHUB_NAMESPACE is required"; \
		exit 1; \
	fi
	$(DOCKER) build -t $(DOCKERHUB_NAMESPACE)/paper-monitor-$*:$(IMAGE_TAG) $*

push-%: build-%
	$(DOCKER) push $(DOCKERHUB_NAMESPACE)/paper-monitor-$*:$(IMAGE_TAG)
