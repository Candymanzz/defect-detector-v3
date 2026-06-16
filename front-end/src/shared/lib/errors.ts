export function errorMessage(error: unknown) {
  if (isHttpErrorLike(error)) {
    const responseMessage = parseHttpErrorMessage(error.responseBody);

    if (responseMessage) {
      return responseMessage;
    }
  }

  return error instanceof Error ? error.message : String(error);
}

function isHttpErrorLike(error: unknown): error is { responseBody: string } {
  return typeof error === "object" && error !== null && "responseBody" in error;
}

function parseHttpErrorMessage(responseBody: string) {
  if (!responseBody.trim()) {
    return "";
  }

  try {
    const parsed = JSON.parse(responseBody) as unknown;

    if (typeof parsed === "object" && parsed !== null) {
      const message = "detail" in parsed ? parsed.detail : "error" in parsed ? parsed.error : "";
      return typeof message === "string" ? message : JSON.stringify(message);
    }
  } catch {
    return responseBody;
  }

  return responseBody;
}
