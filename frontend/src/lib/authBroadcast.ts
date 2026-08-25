const CHANNEL_NAME = 'taskflow-auth';
const SESSION_ENDED = 'session-ended';

export function broadcastSessionEnded(): void {
  if (!('BroadcastChannel' in window)) return;
  const channel = new BroadcastChannel(CHANNEL_NAME);
  channel.postMessage(SESSION_ENDED);
  channel.close();
}

export function listenForSessionEnded(listener: () => void): () => void {
  if (!('BroadcastChannel' in window)) return () => undefined;
  const channel = new BroadcastChannel(CHANNEL_NAME);
  channel.addEventListener('message', event => {
    if (event.data === SESSION_ENDED) listener();
  });
  return () => channel.close();
}
