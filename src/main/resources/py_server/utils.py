
import json
from ollama import Client
from openai import OpenAI
from config import CONFIG
from function_calling import TOOLS_SCHEMA, getFunctionArguments, TOOL_MAPPING

import os

def getAPIKey():

    path_to_key = os.path.join(os.getenv("LICENSE_TOOL_PROJECT"), ".license-tool/openai_key.txt")
    with open(path_to_key, "r", encoding="utf-8") as file:
        return file.read()

def promptOpenAI(model, prompt, history):
    client = OpenAI(api_key=getAPIKey())
    history.append({'role':'user',"content":prompt})
    response = client.chat.completions.create(model=model, messages=history, tools=TOOLS_SCHEMA)
    response = json.loads(response.to_json())
    response = response.get("choices")[0]
    tool_calls = response.get("finish_reason") == "tool_calls"
    if tool_calls:
        for call in response.get("message").get("tool_calls"):
            func_name = call.get("function").get("name")
            func = TOOL_MAPPING.get(func_name)
            args = json.loads(call.get("function").get("arguments"))
            args = getFunctionArguments(func_name, args)
            function_response = func(*args)
            history.append({
                "role": "user",
                "content": f"Use this information in your response: {function_response}",
            })
        response = client.chat.completions.create(model=model, messages=history)
        response = json.loads(response.to_json())
        response = response["choices"][0]
    return response["message"]["content"]

def promptOllama(host, model, prompt, history):
    client = Client( host = host )
    history.append({'role':'user',"content":prompt})
    response = client.chat(
        model=model, 
        messages=history, 
        tools=TOOLS_SCHEMA
    )
    if not response["message"].get("tool_calls"):
        return response["message"]["content"]
    else:
        for tool in response["message"]["tool_calls"]:
            function_to_call = TOOL_MAPPING[tool["function"]["name"]]
            print(f"Calling function {function_to_call}...")
            args = getFunctionArguments(tool["function"]["name"], tool["function"]["arguments"])
            function_response = function_to_call(*args)
            history.append({
                "role": "tool",
                "content": f"Use this information in your response: {function_response}",
            })
    final_response = client.chat(model=model, messages=history)
    return final_response["message"]["content"]