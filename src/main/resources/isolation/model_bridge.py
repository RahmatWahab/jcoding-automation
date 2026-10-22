"""Sandbox-local TCP to Unix-socket bridge. No external network or provider key."""
import os
import select
import socket
import subprocess
import sys
import threading

slots = threading.BoundedSemaphore(8)

def relay(client):
    upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        upstream.connect('/home/worker/provider.sock')
        peers = {client: upstream, upstream: client}
        while True:
            readable, _, _ = select.select(list(peers), [], [], 30)
            if not readable:
                continue
            for current in readable:
                data = current.recv(65536)
                if not data:
                    return
                peers[current].sendall(data)
    except OSError:
        pass
    finally:
        client.close()
        upstream.close()
        slots.release()

server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(('127.0.0.1', 18443))
server.listen(8)

def serve():
    while True:
        client, _ = server.accept()
        if slots.acquire(blocking=False):
            threading.Thread(target=relay, args=(client,), daemon=True).start()
        else:
            client.close()

threading.Thread(target=serve, daemon=True).start()
# Keep bridge alive for the child; controller supervises and kills the whole tree.
try:
    sys.exit(subprocess.call(sys.argv[1:]))
finally:
    server.close()
