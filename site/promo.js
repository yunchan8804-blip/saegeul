/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */

(() => {
  const SVG_NS = "http://www.w3.org/2000/svg";
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const filterDefs = document.querySelector("#liquid-filter-defs");
  const glasses = [...document.querySelectorAll(".liquid-glass")];
  const refractiveGlasses = glasses.filter((glass) => glass.classList.contains("liquid-glass-refract"));

  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

  const convexSquircle = (x) => Math.pow(10 - Math.pow(1 - x, 8), 0.25);

  const calculateRefractionProfile = (glassThickness, bezelWidth, refractiveIndex, samples = 128) => {
    const eta = 1 / refractiveIndex;

    const refract = (normalX, normalY) => {
      const dot = normalY;
      const k = 1 - eta * eta * (1 - dot * dot);

      if (k < 0) {
        return null;
      }

      const root = Math.sqrt(k);
      return [
        -(eta * dot + root) * normalX,
        eta - (eta * dot + root) * normalY,
      ];
    };

    return Array.from({ length: samples }, (_, index) => {
      const x = index / samples;
      const y = convexSquircle(x);
      const delta = x < 1 ? 0.0001 : -0.0001;
      const derivative = (convexSquircle(x + delta) - y) / delta;
      const magnitude = Math.hypot(derivative, 1);
      const refracted = refract(-derivative / magnitude, -1 / magnitude);

      if (!refracted || Math.abs(refracted[1]) < 0.0001) {
        return 0;
      }

      const remainingHeight = y * bezelWidth + glassThickness;
      return refracted[0] * (remainingHeight / refracted[1]);
    });
  };

  const roundedRectDistance = (x, y, width, height, radius) => {
    const qx = Math.abs(x - width / 2) - (width / 2 - radius);
    const qy = Math.abs(y - height / 2) - (height / 2 - radius);
    const outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
    const inside = Math.min(Math.max(qx, qy), 0);
    return outside + inside - radius;
  };

  const createMaps = (cssWidth, cssHeight, cssRadius) => {
    const longestSide = Math.max(cssWidth, cssHeight);
    const mapRatio = Math.min(1, 760 / longestSide);
    const width = Math.max(24, Math.round(cssWidth * mapRatio));
    const height = Math.max(24, Math.round(cssHeight * mapRatio));
    const radius = Math.min(cssRadius * mapRatio, Math.min(width, height) / 2 - 1);
    const bezelCss = clamp(cssRadius * 0.58, 18, 24);
    const bezel = bezelCss * mapRatio;
    const profile = calculateRefractionProfile(92, bezelCss, 1.32);
    const maximumDisplacement = Math.max(...profile.map(Math.abs), 1);
    const displacementCanvas = document.createElement("canvas");
    const specularCanvas = document.createElement("canvas");
    displacementCanvas.width = specularCanvas.width = width;
    displacementCanvas.height = specularCanvas.height = height;

    const displacementContext = displacementCanvas.getContext("2d", { alpha: false });
    const specularContext = specularCanvas.getContext("2d");
    const displacementImage = displacementContext.createImageData(width, height);
    const specularImage = specularContext.createImageData(width, height);
    const lightX = 0.72;
    const lightY = 0.69;

    for (let offset = 0; offset < displacementImage.data.length; offset += 4) {
      displacementImage.data[offset] = 128;
      displacementImage.data[offset + 1] = 128;
      displacementImage.data[offset + 2] = 128;
      displacementImage.data[offset + 3] = 255;
    }

    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        const offset = (y * width + x) * 4;
        const signedDistance = roundedRectDistance(x, y, width, height, radius);
        const distanceInside = Math.max(0, -signedDistance);

        if (signedDistance > 1 || distanceInside >= bezel) {
          continue;
        }

        const sample = 0.75;
        const gradientX = roundedRectDistance(x + sample, y, width, height, radius)
          - roundedRectDistance(x - sample, y, width, height, radius);
        const gradientY = roundedRectDistance(x, y + sample, width, height, radius)
          - roundedRectDistance(x, y - sample, width, height, radius);
        const gradientLength = Math.hypot(gradientX, gradientY) || 1;
        const inwardX = -gradientX / gradientLength;
        const inwardY = -gradientY / gradientLength;
        const profileIndex = Math.min(
          Math.floor((distanceInside / bezel) * profile.length),
          profile.length - 1,
        );
        const profileDisplacement = profile[profileIndex] || 0;
        const red = Math.round(128 + inwardX * (profileDisplacement / cssWidth) * 255);
        const green = Math.round(128 + inwardY * (profileDisplacement / cssHeight) * 255);

        displacementImage.data[offset] = clamp(red, 0, 255);
        displacementImage.data[offset + 1] = clamp(green, 0, 255);
        displacementImage.data[offset + 2] = 128;
        displacementImage.data[offset + 3] = 255;

        const facingLight = Math.abs(inwardX * lightX + inwardY * lightY);
        const outerRim = 1 - clamp(distanceInside / Math.max(1, 1.6 * mapRatio), 0, 1);
        const highlight = Math.pow(facingLight, 2) * Math.sqrt(outerRim);
        specularImage.data[offset] = 244;
        specularImage.data[offset + 1] = 251;
        specularImage.data[offset + 2] = 255;
        specularImage.data[offset + 3] = Math.round(highlight * 230);
      }
    }

    displacementContext.putImageData(displacementImage, 0, 0);
    specularContext.putImageData(specularImage, 0, 0);

    return {
      displacementUrl: displacementCanvas.toDataURL("image/png"),
      specularUrl: specularCanvas.toDataURL("image/png"),
      maximumDisplacement,
    };
  };

  const svgElement = (name, attributes) => {
    const element = document.createElementNS(SVG_NS, name);
    Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, String(value)));
    return element;
  };

  const buildFilter = (glass, index) => {
    const rect = glass.getBoundingClientRect();
    const cssWidth = Math.max(1, Math.round(rect.width));
    const cssHeight = Math.max(1, Math.round(rect.height));

    if (glass.__liquidSize
      && Math.abs(glass.__liquidSize.width - cssWidth) < 4
      && Math.abs(glass.__liquidSize.height - cssHeight) < 4) {
      return;
    }

    const filterId = `liquid-glass-${index}`;
    const previousFilter = document.querySelector(`#${filterId}`);
    previousFilter?.remove();

    const computedStyle = getComputedStyle(glass);
    const cssRadius = Math.min(
      parseFloat(computedStyle.borderTopLeftRadius) || 34,
      Math.min(cssWidth, cssHeight) / 2,
    );
    const maps = createMaps(cssWidth, cssHeight, cssRadius);
    const baseScale = glass.classList.contains("glass-nav")
      ? 1
      : rect.height > 180
        ? 1.75
        : 1.35;
    const filter = svgElement("filter", {
      id: filterId,
      x: 0,
      y: 0,
      width: 1,
      height: 1,
      filterUnits: "objectBoundingBox",
      primitiveUnits: "objectBoundingBox",
      "color-interpolation-filters": "sRGB",
    });
    const displacementImage = svgElement("feImage", {
      x: 0,
      y: 0,
      width: 1,
      height: 1,
      preserveAspectRatio: "none",
      result: "displacement-map",
      href: maps.displacementUrl,
    });
    const blurRadiusPx = 5;
    const backgroundBlur = svgElement("feGaussianBlur", {
      in: "SourceGraphic",
      stdDeviation: `${(blurRadiusPx / cssWidth).toFixed(5)} ${(blurRadiusPx / cssHeight).toFixed(5)}`,
      result: "blurred-source",
    });
    const displacement = svgElement("feDisplacementMap", {
      in: "blurred-source",
      in2: "displacement-map",
      scale: baseScale,
      xChannelSelector: "R",
      yChannelSelector: "G",
      result: "refracted",
    });
    const specularImage = svgElement("feImage", {
      x: 0,
      y: 0,
      width: 1,
      height: 1,
      preserveAspectRatio: "none",
      result: "specular-map",
      href: maps.specularUrl,
    });
    const specularBlur = svgElement("feGaussianBlur", {
      in: "specular-map",
      stdDeviation: 0.0008,
      result: "specular-bloom",
    });
    const blend = svgElement("feBlend", {
      in: "refracted",
      in2: "specular-bloom",
      mode: "screen",
    });

    filter.append(displacementImage, backgroundBlur, displacement, specularImage, specularBlur, blend);
    filterDefs.append(filter);
    glass.style.setProperty("-webkit-backdrop-filter", `url("#${filterId}")`);
    glass.style.setProperty("backdrop-filter", `url("#${filterId}")`);
    const filterAccepted = glass.style.getPropertyValue("backdrop-filter")
      || glass.style.getPropertyValue("-webkit-backdrop-filter");
    glass.dataset.liquidActive = filterAccepted ? "true" : "false";
    glass.__liquidSize = { width: cssWidth, height: cssHeight };
    glass.__liquidDisplacement = displacement;
    glass.__liquidBaseScale = baseScale;
  };

  const buildAllFilters = () => {
    if (!filterDefs) {
      return;
    }

    refractiveGlasses.forEach(buildFilter);
  };

  buildAllFilters();

  if ("ResizeObserver" in window) {
    let resizeFrame = 0;
    const resizeObserver = new ResizeObserver(() => {
      if (!resizeFrame) {
        resizeFrame = requestAnimationFrame(() => {
          buildAllFilters();
          resizeFrame = 0;
        });
      }
    });
    refractiveGlasses.forEach((glass) => resizeObserver.observe(glass));
  }

  glasses.forEach((glass) => {
    let frame = 0;
    let pointerX = 0;
    let pointerY = 0;

    const paint = () => {
      const rect = glass.getBoundingClientRect();
      const x = clamp(((pointerX - rect.left) / rect.width) * 100, 0, 100);
      const y = clamp(((pointerY - rect.top) / rect.height) * 100, 0, 100);

      glass.style.setProperty("--glint-x", `${x}%`);
      glass.style.setProperty("--glint-y", `${y}%`);
      if (glass.__liquidDisplacement && !reduceMotion.matches) {
        const distanceFromCenter = Math.hypot(x - 50, y - 50) / 70.7;
        const scale = glass.__liquidBaseScale * (1.12 - distanceFromCenter * 0.22);
        glass.__liquidDisplacement.setAttribute("scale", scale.toFixed(2));
      }

      frame = 0;
    };

    glass.addEventListener("pointermove", (event) => {
      pointerX = event.clientX;
      pointerY = event.clientY;

      if (!frame) {
        frame = requestAnimationFrame(paint);
      }
    }, { passive: true });

    glass.addEventListener("pointerleave", () => {
      if (frame) {
        cancelAnimationFrame(frame);
        frame = 0;
      }

      glass.style.removeProperty("--glint-x");
      glass.style.removeProperty("--glint-y");

      if (glass.__liquidDisplacement) {
        glass.__liquidDisplacement.setAttribute("scale", glass.__liquidBaseScale.toFixed(2));
      }
    }, { passive: true });
  });
})();
