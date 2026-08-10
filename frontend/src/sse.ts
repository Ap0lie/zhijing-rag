export interface ServerSentEvent {
  type: string;
  data: string;
}

function nextBoundary(buffer: string): { index: number; length: number } | null {
  const lf = buffer.indexOf("\n\n");
  const crlf = buffer.indexOf("\r\n\r\n");
  if (lf < 0 && crlf < 0) {
    return null;
  }
  if (lf < 0 || (crlf >= 0 && crlf < lf)) {
    return { index: crlf, length: 4 };
  }
  return { index: lf, length: 2 };
}

function parseEvent(block: string): ServerSentEvent | null {
  let type = "message";
  const data: string[] = [];
  for (const line of block.replaceAll("\r\n", "\n").split("\n")) {
    if (!line || line.startsWith(":")) {
      continue;
    }
    const separator = line.indexOf(":");
    const field = separator < 0 ? line : line.slice(0, separator);
    const value = separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "");
    if (field === "event") {
      type = value;
    } else if (field === "data") {
      data.push(value);
    }
  }
  return data.length > 0 ? { type, data: data.join("\n") } : null;
}

export async function consumeEventStream(
  response: Response,
  onEvent: (event: ServerSentEvent) => void,
): Promise<void> {
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let complete = false;

  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) {
        complete = true;
        buffer += decoder.decode();
        break;
      }
      buffer += decoder.decode(chunk.value, { stream: true });
      let boundary = nextBoundary(buffer);
      while (boundary) {
        const event = parseEvent(buffer.slice(0, boundary.index));
        buffer = buffer.slice(boundary.index + boundary.length);
        if (event) {
          onEvent(event);
        }
        boundary = nextBoundary(buffer);
      }
    }

    const finalEvent = parseEvent(buffer);
    if (finalEvent) {
      onEvent(finalEvent);
    }
  } finally {
    if (!complete) {
      await reader.cancel().catch(() => undefined);
    }
  }
}
