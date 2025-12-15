
"""
This file defines the functions that are callable from Java.
"""

import json
from utils import promptOllama
from utils import promptOpenAI
from ollama import Client
from openai import OpenAI
from config import CONFIG
from model_list import models
from function_calling import TOOLS_SCHEMA, getFunctionArguments, TOOL_MAPPING

def promptModel(host, model, prompt, history):
    if model in models["ollama"]:
        return promptOllama(host, model, prompt, history)
    elif model in models["openai"]:
        return promptOpenAI(model, prompt, history)

def setWorkingDirectory(directory):
    CONFIG.setCurrentWorkingDirectory(directory)

def add(a, b):
    return a + b

def subtract(a, b):
    return a - b