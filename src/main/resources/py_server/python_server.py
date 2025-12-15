#!/usr/bin/env python3
import socket, json, threading
import api_functions
import inspect, traceback
import os
import logging
from logging.handlers import RotatingFileHandler

FUNCTIONS = {name:func for name, func in inspect.getmembers(api_functions, inspect.isfunction)}

def setup_logging():
    cwd = None
    try:
        cwd = api_functions.CONFIG.getCurrentWorkingDirectory()
    except Exception:
        cwd = os.getcwd()
    if not cwd:
        cwd = os.getcwd()

    dirpath = os.path.join(cwd, ".license-tool")
    os.makedirs(dirpath, exist_ok=True)
    log_path = os.path.join(dirpath, "python_interaction.log")

    logger = logging.getLogger("python_server")
    logger.setLevel(logging.INFO)

    if not logger.handlers:
        fmt = logging.Formatter("%(asctime)s %(levelname)s %(threadName)s %(message)s")
        sh = logging.StreamHandler()
        sh.setFormatter(fmt)
        fh = RotatingFileHandler(log_path, maxBytes=10 * 1024 * 1024, backupCount=5, encoding="utf-8")
        fh.setFormatter(fmt)
        logger.addHandler(sh)
        logger.addHandler(fh)

    return logger

logger = setup_logging()

def handle_request(json_request):
    try:
        func = json_request.get("function")
        args = json_request.get("args", [])
        if func in FUNCTIONS:
            result = FUNCTIONS.get(func)(*args)
            return {"result": result}
        else:
            return {"error": "Unknown function"}
    except Exception as e:
        logger.exception("Exception while handling request: %s", e.__traceback__)
        traceback.print_exception(type(e), e, e.__traceback__)
        return {"result": "Error... please check server console for more details"}

def handle_client(conn, addr):
    print(f"[+] Connected by {addr}")
    logger.info("[+] Connected by %s", addr)
    with conn:
        buffer = ""
        while True:
            try:
                data = conn.recv(1024)
                if not data:
                    logger.info("[-] Client %s disconnected", addr)
                    print(f"[-] Client {addr} disconnected")
                    break

                buffer += data.decode()

                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    json_request = json.loads(line)
                    logger.info("[>] Received from %s: %s", addr, json_request)
                    print(f"[>] Received from {addr}: {json_request}")
                    response = handle_request(json_request)
                    logger.info("[<] Responding to %s: %s", addr, response)
                    response_str = json.dumps(response) + "\n"
                    conn.sendall(response_str.encode())
            except ConnectionResetError:
                logger.warning("[!] Client %s forcibly closed the connection", addr)
                print(f"[!] Client {addr} forcibly closed the connection")
                break
            except json.JSONDecodeError:
                logger.exception("Unexpected error handling client %s", addr)
                print(f"[!] Invalid JSON from {addr}")
                conn.sendall(b'{"error": "Invalid JSON"}\n')


def startServer(host='localhost', port=9999):
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.bind((host, port))
    server_socket.listen()
    print(f"[o] Python server listening on {host}:{port}")
    logger.info("[o] Python server listening on %s:%d", host, port)

    try:
        while True:
            conn, addr = server_socket.accept()
            thread = threading.Thread(target=handle_client, args=(conn, addr))
            thread.start()
            print(f"[=] Active threads: {threading.active_count() - 1}")
            logger.info("[=] Active threads: %d", threading.active_count() - 1)
    except KeyboardInterrupt:
        print("\n[!] Server shutting down")
        logger.info("[!] Server shutting down")
    finally:
        server_socket.close()

if __name__ == "__main__":
    print("[*] Starting Python server...")
    print("[*] Currtent working directory:", api_functions.CONFIG.getCurrentWorkingDirectory())
    logger.info("[*] Starting Python server...")
    logger.info("[*] Current working directory: %s", api_functions.CONFIG.getCurrentWorkingDirectory())
    startServer()
