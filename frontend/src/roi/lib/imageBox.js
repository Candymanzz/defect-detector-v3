export const computeContainBox = (container, imageSize) => {
  if (!container || !imageSize.width || !imageSize.height) {
    return { left: 0, top: 0, width: 0, height: 0 };
  }
  const containerWidth = container.clientWidth;
  const containerHeight = container.clientHeight;
  if (!containerWidth || !containerHeight) {
    return { left: 0, top: 0, width: 0, height: 0 };
  }

  const containerAspect = containerWidth / containerHeight;
  const imageAspect = imageSize.width / imageSize.height;

  let drawWidth = containerWidth;
  let drawHeight = containerHeight;
  let offsetLeft = 0;
  let offsetTop = 0;

  if (imageAspect > containerAspect) {
    drawHeight = containerWidth / imageAspect;
    offsetTop = (containerHeight - drawHeight) / 2;
  } else {
    drawWidth = containerHeight * imageAspect;
    offsetLeft = (containerWidth - drawWidth) / 2;
  }

  return {
    left: offsetLeft,
    top: offsetTop,
    width: drawWidth,
    height: drawHeight
  };
};
export const getNormalizedPointInImageBox = ({ clientX, clientY, containerRect, imageBox }) => {
  if (!containerRect.width || !containerRect.height) return null;

  const activeBox =
    imageBox.width > 0 && imageBox.height > 0
      ? imageBox
      : { left: 0, top: 0, width: containerRect.width, height: containerRect.height };

  const localX = clientX - containerRect.left;
  const localY = clientY - containerRect.top;
  const inImageX = localX - activeBox.left;
  const inImageY = localY - activeBox.top;

  if (inImageX < 0 || inImageY < 0 || inImageX > activeBox.width || inImageY > activeBox.height) {
    return null;
  }

  return {
    x: Math.max(0, Math.min(1, inImageX / activeBox.width)),
    y: Math.max(0, Math.min(1, inImageY / activeBox.height))
  };
};
