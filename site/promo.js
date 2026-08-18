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

      if (k < 0) return null;

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
    if (!filterDefs) return;
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

  /* ==========================================================================
     Toast Notification Utility
     ========================================================================== */
  const showToast = (message) => {
    let toast = document.querySelector("#global-toast");
    if (!toast) {
      toast = document.createElement("div");
      toast.id = "global-toast";
      toast.className = "global-toast";
      toast.setAttribute("role", "status");
      toast.setAttribute("aria-live", "polite");
      document.body.appendChild(toast);
    }
    toast.textContent = message;
    toast.classList.add("is-visible");
    clearTimeout(toast.__timer);
    toast.__timer = setTimeout(() => {
      toast.classList.remove("is-visible");
    }, 2400);
  };

  /* ==========================================================================
     One-Click Copy Buttons
     ========================================================================== */
  document.querySelectorAll("[data-copy]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const textToCopy = btn.getAttribute("data-copy");
      if (!textToCopy) return;

      try {
        await navigator.clipboard.writeText(textToCopy);
        const originalText = btn.getAttribute("data-copied-label") || "클립보드에 복사되었습니다.";
        showToast(originalText);
      } catch {
        showToast("클립보드 복사에 실패했습니다.");
      }
    });
  });

  /* ==========================================================================
     Interactive Hangul Demo Controller
     ========================================================================== */
  const hangulDemoTabs = document.querySelectorAll(".hangul-demo-tab");
  const hangulDemoDisplay = document.querySelector(".hangul-demo-display");
  if (hangulDemoTabs.length && hangulDemoDisplay) {
    const demos = {
      typo: {
        from: "dkssudgktpdy",
        to: "안녕하세요",
        tag: "오타 자동복구",
        desc: "영문 상태로 잘못 입력한 한글 단어를 기기 내에서 즉시 분석하여 올바른 한글 어절로 복구합니다.",
      },
      chosung: {
        from: "ㄱㅅㅎㄴㄷ",
        to: "감사합니다",
        tag: "초성 검색",
        desc: "자주 쓰는 문구, 클립보드 내역, 이모지를 초성만으로 빠르게 검색하여 즉시 입력합니다.",
      },
      josa: {
        from: "새글을 쓴다 / 키보드가 열린다",
        to: "받침별 자동 판별",
        tag: "조사 보정",
        desc: "앞 음절의 종성(받침)과 ㄹ 받침 예외 규칙을 정밀하게 감지하여 올바른 조사를 추천합니다.",
      },
      hanja: {
        from: "가 → 可",
        to: "옳을 가 (음훈 안내)",
        tag: "한자 음훈",
        desc: "음과 훈을 함께 확인하며 변환합니다. 일회성으로 치환되어 한자 모드가 고정되지 않습니다.",
      },
      dict: {
        from: "31,808 표제어",
        to: "오프라인 표준국어대사전",
        tag: "오프라인 사전",
        desc: "인터넷 연결 없이도 낱말의 뜻과 표준 표기를 기기 내에서 즉시 확인합니다. 데이터 유출 0바이트.",
      },
    };

    hangulDemoTabs.forEach((tab) => {
      tab.addEventListener("click", () => {
        const key = tab.getAttribute("data-demo-key");
        const data = demos[key];
        if (!data) return;

        hangulDemoTabs.forEach((t) => {
          t.classList.remove("is-active");
          t.setAttribute("aria-selected", "false");
        });
        tab.classList.add("is-active");
        tab.setAttribute("aria-selected", "true");

        hangulDemoDisplay.innerHTML = `
          <div class="demo-pill-badge">${data.tag}</div>
          <div class="demo-transform-box">
            <span class="demo-from-val">${data.from}</span>
            <span class="demo-arrow-icon" aria-hidden="true">→</span>
            <span class="demo-to-val">${data.to}</span>
          </div>
          <p class="demo-desc-text">${data.desc}</p>
        `;
      });
    });
  }

  /* ==========================================================================
     Interactive AI Writing Diff Controller
     ========================================================================== */
  const aiToneButtons = document.querySelectorAll(".ai-tone-btn");
  const aiDiffContainer = document.querySelector(".ai-diff-container");
  if (aiToneButtons.length && aiDiffContainer) {
    const aiDemos = {
      correct: {
        title: "교정 & 맞춤법",
        original: "내일 봬요! 이번 프로젝트 정말 수고하셧어요",
        result: '내일 <span class="diff-highlight-add">봬요</span>! 이번 프로젝트 정말 수고<span class="diff-highlight-add">하셨어요</span>.',
        actionNote: "틀린 맞춤법과 띄어쓰기만 짚어내고 기존 문맥을 온전히 보존합니다.",
      },
      business: {
        title: "업무 메일체",
        original: "보내주신 자료 잘 봤습니다 수정사항 확인 부탁해요",
        result: '보내주신 자료 확인하였습니다. <span class="diff-highlight-add">요청드린 수정사항 검토 부탁드립니다.</span>',
        actionNote: "비즈니스 상황에 걸맞은 격식과 명확한 어조로 가다듬습니다.",
      },
      polite_reject: {
        title: "정중한 거절",
        original: "이번 일정은 참여하기 힘들 것 같아요 죄송합니다",
        result: '제안 주셔서 감사드립니다. <span class="diff-highlight-add">현재 일정상 부득이하게 참여가 어려울 것 같습니다. 양해를 부탁드립니다.</span>',
        actionNote: "상대방의 기분을 상하지 않게 하면서도 단호하고 예의 바르게 거절합니다.",
      },
      casual: {
        title: "카톡체 / 편한 말투",
        original: "오늘 저녁에 시간 되시면 같이 식사하실래요?",
        result: '오늘 저녁에 시간 돼? <span class="diff-highlight-add">같이 밥 먹자! 😊</span>',
        actionNote: "친구 및 지인과의 자연스러운 대화 흐름에 맞게 부드럽게 전환합니다.",
      },
    };

    aiToneButtons.forEach((btn) => {
      btn.addEventListener("click", () => {
        const toneKey = btn.getAttribute("data-tone-key");
        const data = aiDemos[toneKey];
        if (!data) return;

        aiToneButtons.forEach((b) => {
          b.classList.remove("is-active");
          b.setAttribute("aria-selected", "false");
        });
        btn.classList.add("is-active");
        btn.setAttribute("aria-selected", "true");

        aiDiffContainer.innerHTML = `
          <div class="ai-diff-row">
            <div class="diff-box diff-before">
              <span class="diff-label">입력 원문</span>
              <p>${data.original}</p>
            </div>
            <div class="diff-arrow" aria-hidden="true">→</div>
            <div class="diff-box diff-after">
              <span class="diff-label">새글 AI 제안</span>
              <p>${data.result}</p>
            </div>
          </div>
          <p class="ai-action-note">${data.actionNote}</p>
        `;
      });
    });
  }

  /* ==========================================================================
     FAQ Live Search Filter
     ========================================================================== */
  const faqSearchInput = document.querySelector("#faq-search-input");
  const faqItems = document.querySelectorAll(".faq-list details");
  if (faqSearchInput && faqItems.length) {
    faqSearchInput.addEventListener("input", (e) => {
      const query = e.target.value.trim().toLowerCase();
      let matchCount = 0;

      faqItems.forEach((item) => {
        const text = item.textContent.toLowerCase();
        const matches = text.includes(query);
        item.style.display = matches ? "" : "none";
        if (matches) {
          matchCount++;
          if (query.length > 1) {
            item.setAttribute("open", "");
          }
        }
      });

      const countDisplay = document.querySelector("#faq-search-count");
      if (countDisplay) {
        countDisplay.textContent = query ? `${matchCount}개의 질문 일치` : "";
      }
    });
  }

  /* ==========================================================================
     Theme Studio Interactive Simulator
     ========================================================================== */
  const themeData = {
    hanji: {
      badge: "HANJI LIGHT · 한지 라이트",
      spec: "닥나무 한지의 따뜻한 미색 표면(#E9E1D2)과 선명한 붓먹 텍스트, 은은한 적갈색(#B83A32) 엔터키 액센트",
      tokens: [
        { name: "표면", val: "#E9E1D2" },
        { name: "키캡", val: "#FAF6EC" },
        { name: "먹빛", val: "#24201B" },
        { name: "특수키", val: "#D7CDBE" },
        { name: "강조(주홍)", val: "#B83A32" }
      ]
    },
    dancheong: {
      badge: "DANCHEONG DARK · 단청 다크",
      spec: "궁궐의 야경을 연상시키는 묵색 배경(#101918)과 비취색 툴바, 붉은 단청(#C84A3F) 액센트",
      tokens: [
        { name: "표면", val: "#101918" },
        { name: "키캡", val: "#253330" },
        { name: "상아빛", val: "#F4EAD8" },
        { name: "특수키", val: "#192623" },
        { name: "단청홍", val: "#C84A3F" }
      ]
    },
    baegja: {
      badge: "BAEGJA LIGHT · 백자 라이트",
      spec: "조선 백자의 맑고 고결한 순백미(#F5F6F8)와 청화 안료의 깊은 코발트 블루(#1E40AF) 액센트",
      tokens: [
        { name: "표면", val: "#F5F6F8" },
        { name: "키캡", val: "#FFFFFF" },
        { name: "슬레이트", val: "#1E293B" },
        { name: "특수키", val: "#E2E8F0" },
        { name: "청화 코발트", val: "#1E40AF" }
      ]
    },
    cheongja: {
      badge: "CHEONGJA DARK · 청자 다크",
      spec: "고려 비색 청자의 신비로운 옥색 안개(#0F1E1B)와 고풍스러운 황동/황금 상감(#C49A45) 액센트",
      tokens: [
        { name: "비색 표면", val: "#0F1E1B" },
        { name: "옥색 키캡", val: "#1A2F2B" },
        { name: "미스트", val: "#E6F4F1" },
        { name: "특수키", val: "#142421" },
        { name: "황금 상감", val: "#C49A45" }
      ]
    },
    midnight: {
      badge: "MIDNIGHT OLED · 자정 OLED",
      spec: "소비 전력 제로의 완전 무결한 피치 블랙(#000000)과 강렬한 네온 제이드(#00E699) 발광",
      tokens: [
        { name: "OLED 블랙", val: "#000000" },
        { name: "차콜 키캡", val: "#141414" },
        { name: "화이트", val: "#FFFFFF" },
        { name: "다크 특수키", val: "#0C0C0C" },
        { name: "네온 제이드", val: "#00E699" }
      ]
    },
    mist: {
      badge: "SEOUL MIST · 안개 글래스",
      spec: "모던 프로스티드 슬레이트(#182230) 글래스모피즘과 청량한 스카이 사이언(#38BDF8) 액센트",
      tokens: [
        { name: "슬레이트", val: "#182230" },
        { name: "글래스 키캡", val: "#253346" },
        { name: "아이스", val: "#F8FAFC" },
        { name: "특수키", val: "#1B2636" },
        { name: "스카이 사이언", val: "#38BDF8" }
      ]
    }
  };

  const themeTabButtons = document.querySelectorAll(".theme-tab-btn");
  const vkBody = document.querySelector("#vk-body");
  const themeBadge = document.querySelector("#theme-badge");
  const themeSpec = document.querySelector("#theme-spec");
  const themePaletteBar = document.querySelector("#theme-palette-bar");
  const vkTypedText = document.querySelector("#vk-typed-text");
  const vkBtnClear = document.querySelector("#vk-btn-clear");
  const vkKeys = document.querySelectorAll(".vk-key");

  function renderThemePalette(key) {
    const data = themeData[key];
    if (!data || !themePaletteBar) return;

    if (themeBadge) themeBadge.textContent = data.badge;
    if (themeSpec) themeSpec.textContent = data.spec;

    themePaletteBar.innerHTML = data.tokens
      .map(
        (t) => `
        <div class="palette-token-item">
          <span class="palette-token-dot" style="background:${t.val}"></span>
          <span class="palette-token-name">${t.name}</span>
          <span class="palette-token-val">${t.val}</span>
        </div>
      `
      )
      .join("");
  }

  themeTabButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      const themeKey = btn.dataset.theme;
      themeTabButtons.forEach((b) => {
        b.classList.remove("active");
        b.setAttribute("aria-selected", "false");
      });
      btn.classList.add("active");
      btn.setAttribute("aria-selected", "true");

      if (vkBody) {
        vkBody.setAttribute("data-theme", themeKey);
      }
      renderThemePalette(themeKey);
    });
  });

  // Initial render of palette
  renderThemePalette("hanji");

  // Virtual keyboard typing interactions
  vkKeys.forEach((key) => {
    key.addEventListener("click", () => {
      key.classList.add("pressed");
      setTimeout(() => key.classList.remove("pressed"), 120);

      if (!vkTypedText) return;
      const char = key.dataset.key;
      const action = key.dataset.action;

      if (char) {
        vkTypedText.textContent = (vkTypedText.textContent || "") + char;
      } else if (action === "backspace") {
        const text = vkTypedText.textContent || "";
        vkTypedText.textContent = text.slice(0, -1);
      } else if (action === "enter") {
        vkTypedText.textContent = (vkTypedText.textContent || "") + " ↵ ";
      }
    });
  });

  if (vkBtnClear && vkTypedText) {
    vkBtnClear.addEventListener("click", () => {
      vkTypedText.textContent = "";
    });
  }

  /* ==========================================================================
     Scroll Reveal Animation via IntersectionObserver
     ========================================================================== */
  if ("IntersectionObserver" in window && !reduceMotion.matches) {
    const revealElements = document.querySelectorAll(".reveal");
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-revealed");
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.15, rootMargin: "0px 0px -40px 0px" }
    );

    revealElements.forEach((el) => observer.observe(el));
  } else {
    document.querySelectorAll(".reveal").forEach((el) => el.classList.add("is-revealed"));
  }
})();
