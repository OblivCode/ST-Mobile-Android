// ST-Mobile Node Preload Hook
// Intercepts active LLM generation requests across all backends to signal Android host.

const http = require('node:http');
const https = require('node:https');
const net = require('node:net');

const GEN_ROUTE_REGEX = /^\/api\/(backends\/[^/]+|azure|novelai|horde)\/generate(-text|-voice|-image|-video)?\/?(\?.*)?$/;

let activeRequests = 0;
let ipcSocket = null;
const pendingQueue = [];

function flushQueue() {
    while (pendingQueue.length > 0 && ipcSocket && !ipcSocket.destroyed && ipcSocket.writable) {
        const item = pendingQueue[0];
        try {
            const ok = ipcSocket.write(item);
            pendingQueue.shift();
            if (!ok) {
                break;
            }
        } catch (_) {
            break;
        }
    }
}

function connectIpc() {
    const port = parseInt(process.env.ST_GEN_PORT, 10);
    if (!port || isNaN(port)) return;

    try {
        const sock = net.connect({ host: '127.0.0.1', port }, () => {
            ipcSocket = sock;
            const token = process.env.ST_GEN_TOKEN;
            if (token) {
                try {
                    ipcSocket.write(`AUTH ${token}\n`);
                } catch (_) {
                    return;
                }
            }
            flushQueue();
        });
        sock.unref();

        sock.on('drain', () => {
            flushQueue();
        });

        sock.on('error', () => {
            // Suppress socket errors to prevent unhandled exceptions crashing Node
        });

        sock.on('close', () => {
            if (ipcSocket === sock) {
                ipcSocket = null;
            }
            setTimeout(connectIpc, 1500).unref();
        });
    } catch (_) {
        // Suppress connection attempt errors
    }
}

function sendEvent(cmd) {
    const line = cmd + '\n';
    if (ipcSocket && !ipcSocket.destroyed && ipcSocket.writable) {
        try {
            ipcSocket.write(line);
        } catch (_) {
            pendingQueue.push(line);
        }
    } else {
        pendingQueue.push(line);
        if (pendingQueue.length > 50) {
            pendingQueue.shift();
        }
    }
}

function handleIncomingRequest(req, res) {
    const url = req.url || '';
    if (req.method === 'POST' && GEN_ROUTE_REGEX.test(url)) {
        activeRequests++;
        if (activeRequests === 1) {
            sendEvent('GEN_START');
        }

        let finished = false;
        const onEnd = () => {
            if (finished) return;
            finished = true;
            activeRequests = Math.max(0, activeRequests - 1);
            if (activeRequests === 0) {
                sendEvent('GEN_END');
            }
        };

        res.once('finish', onEnd);
        res.once('close', onEnd);
        res.once('error', onEnd);
    }
}

function wrapServer(server) {
    server.on('request', (req, res) => {
        handleIncomingRequest(req, res);
    });
    return server;
}

const origHttpCreate = http.createServer;
http.createServer = function(...args) {
    return wrapServer(origHttpCreate.apply(this, args));
};

const origHttpsCreate = https.createServer;
https.createServer = function(...args) {
    return wrapServer(origHttpsCreate.apply(this, args));
};

connectIpc();
