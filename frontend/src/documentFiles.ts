import { apiRequest } from "./api";
import type {
  DocumentFormatCapability,
  DocumentFormatsResponse,
} from "./types";

const FORMATS_CACHE_TTL_MS = 60_000;

let formatsCache: DocumentFormatsResponse | null = null;
let formatsExpiresAt = 0;
let formatsRequest: Promise<DocumentFormatsResponse> | null = null;

export function loadDocumentFormats() {
  if (formatsCache && Date.now() < formatsExpiresAt) {
    return Promise.resolve(formatsCache);
  }
  if (!formatsRequest) {
    formatsRequest = apiRequest<DocumentFormatsResponse>("/api/v1/document-formats")
      .then((response) => {
        formatsCache = response;
        formatsExpiresAt = Date.now() + FORMATS_CACHE_TTL_MS;
        return response;
      })
      .catch((error: unknown) => {
        formatsCache = null;
        formatsExpiresAt = 0;
        throw error;
      })
      .finally(() => {
        formatsRequest = null;
      });
  }
  return formatsRequest;
}

export function resetDocumentFormatsCache() {
  formatsCache = null;
  formatsExpiresAt = 0;
  formatsRequest = null;
}

export function enabledDocumentFormats(response: DocumentFormatsResponse) {
  return response.formats.filter((format) => format.enabled);
}

export function fileAcceptValue(formats: DocumentFormatCapability[]) {
  return formats.flatMap((format) => [
    ...format.extensions,
    ...format.mediaTypes,
  ]).join(",");
}

export function titleFromFilename(
  filename: string,
  formats: DocumentFormatCapability[],
) {
  const extension = formats
    .flatMap((format) => format.extensions)
    .sort((left, right) => right.length - left.length)
    .find((candidate) => filename.toLowerCase().endsWith(candidate.toLowerCase()));
  return extension ? filename.slice(0, -extension.length) : filename;
}

export function validateDocumentFile(
  file: File,
  formats: DocumentFormatCapability[],
): string | null {
  const format = formats.find((candidate) =>
    candidate.extensions.some((extension) =>
      file.name.toLowerCase().endsWith(extension.toLowerCase())));
  if (!format) {
    return `请选择支持的文件：${formats.flatMap((item) => item.extensions).join("、")}`;
  }
  if (file.size === 0 || file.size > format.maxFileSizeBytes) {
    const maximumMiB = Math.floor(format.maxFileSizeBytes / 1024 / 1024);
    return `${format.displayName} 必须非空且不超过 ${maximumMiB} MiB`;
  }
  return null;
}

export function documentFormatForFilename(
  filename: string,
  formats: DocumentFormatCapability[],
) {
  return formats.find((candidate) =>
    candidate.extensions.some((extension) =>
      filename.toLowerCase().endsWith(extension.toLowerCase())))?.format ?? null;
}
