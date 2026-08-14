package com.thoitiettxl.eta.core

import org.json.JSONObject

/**
 * Agent 浏览器注入页面的读取与交互脚本。
 *
 * DOM 遍历保留节点、时间、字段和输出上限，避免网页规模导致 Binder 或模型上下文溢出。
 */
internal object BrowserDomScripts {
    fun wrap(body: String): String =
        """
        (function() {
          var MAX_FIELD_CHARS = 240;
          var MAX_URL_CHARS = 320;
          var MAX_DOCUMENT_CHARS = 200000;
          var MAX_SELECTOR_CHARS = 240;
          var MAX_OBSERVED_ELEMENTS = 32;
          var REF_STATE_KEY = '__etaBrowserRefStateV1';
          var HELP_TARGET_KEY = '__etaBrowserHelpTargetV1';

          function boundedString(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_FIELD_CHARS);
            var text = String(value == null ? '' : value);
            if (text.length > max * 4) text = text.slice(0, max * 4);
            return text.slice(0, max);
          }
          function cleanInline(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_FIELD_CHARS);
            return boundedString(value, max * 4)
              .replace(/[\t\r\n ]+/g, ' ')
              .trim()
              .slice(0, max);
          }
          function cleanBlock(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_DOCUMENT_CHARS);
            return boundedString(value, max * 2)
              .replace(/\r/g, '')
              .replace(/[\t ]+\n/g, '\n')
              .replace(/\n[\t ]+/g, '\n')
              .replace(/[\t ]{2,}/g, ' ')
              .replace(/\n{3,}/g, '\n\n')
              .trim()
              .slice(0, max);
          }
          function visible(element) {
            if (!element || !(element instanceof Element)) return false;
            if (element.tagName && element.tagName.toLowerCase() === 'input' &&
                String(element.getAttribute('type') || '').toLowerCase() === 'hidden') return false;
            var ancestor = element;
            var depth = 0;
            while (ancestor && depth < 40) {
              if (ancestor.hidden || ancestor.hasAttribute('inert') ||
                  ancestor.getAttribute('aria-hidden') === 'true') return false;
              var style = window.getComputedStyle(ancestor);
              if (style.display === 'none' || style.visibility === 'hidden' ||
                  style.visibility === 'collapse' || style.contentVisibility === 'hidden' ||
                  Number(style.opacity || 1) <= 0) return false;
              if ((style.clip && style.clip !== 'auto') ||
                  (style.clipPath && style.clipPath !== 'none')) return false;
              ancestor = ancestor.parentElement;
              depth++;
            }
            if (ancestor) return false;
            var rect = element.getBoundingClientRect();
            var tag = String(element.tagName || '').toLowerCase();
            if (window.getComputedStyle(element).display === 'contents' || tag === 'html' || tag === 'body') {
              return true;
            }
            if (rect.right + window.scrollX <= 0 || rect.bottom + window.scrollY <= 0) return false;
            return rect.width > 0 && rect.height > 0 && element.getClientRects().length > 0;
          }
          function enabled(element) {
            return visible(element) && !element.disabled &&
              element.getAttribute('aria-disabled') !== 'true' &&
              !element.hasAttribute('inert');
          }
          function editable(element) {
            if (!enabled(element) || element.readOnly) return false;
            if (element.isContentEditable) return true;
            var tag = (element.tagName || '').toLowerCase();
            if (tag === 'textarea') return true;
            if (tag !== 'input') return false;
            var type = String(element.getAttribute('type') || 'text').toLowerCase();
            return !['hidden','file','button','submit','reset','image','checkbox','radio'].includes(type);
          }
          function valueBearing(element) {
            var tag = String(element.tagName || '').toLowerCase();
            return element.isContentEditable || tag === 'input' || tag === 'textarea' || tag === 'select';
          }
          function cssEscape(value) {
            if (window.CSS && CSS.escape) return CSS.escape(boundedString(value, 180));
            return boundedString(value, 180).replace(/[^a-zA-Z0-9_-]/g, function(ch) {
              return '\\' + ch.charCodeAt(0).toString(16) + ' ';
            });
          }
          function selectorFor(element) {
            if (!element || !(element instanceof Element)) return null;
            if (element.id) {
              var byId = '#' + cssEscape(element.id);
              try { if (document.querySelectorAll(byId).length === 1) return byId; } catch (_) {}
            }
            var parts = [];
            var node = element;
            var depth = 0;
            while (node && node.nodeType === Node.ELEMENT_NODE && node !== document.body && depth < 20) {
              var part = String(node.tagName || '').toLowerCase();
              var parent = node.parentElement;
              if (!part) break;
              if (parent) {
                var position = 0;
                var count = 0;
                for (var index = 0; index < parent.children.length && index < 2000; index++) {
                  if (parent.children[index].tagName === node.tagName) {
                    count++;
                    if (parent.children[index] === node) position = count;
                  }
                }
                if (count > 1 && position > 0) part += ':nth-of-type(' + position + ')';
              }
              parts.unshift(part);
              var candidate = parts.join(' > ');
              if (candidate.length > MAX_SELECTOR_CHARS) break;
              try { if (document.querySelectorAll(candidate).length === 1) return candidate; } catch (_) {}
              node = parent;
              depth++;
            }
            return boundedString(parts.join(' > '), MAX_SELECTOR_CHARS) || null;
          }
          function absoluteUrl(value) {
            if (!value) return null;
            try {
              var parsed = new URL(boundedString(value, 2048), document.baseURI);
              return boundedString(parsed.href, MAX_URL_CHARS);
            } catch (_) { return null; }
          }
          function collectVisibleText(root, maxChars, nodeLimit, sharedDeadline) {
            var limit = Math.max(0, Math.min(Number(maxChars) || 0, MAX_DOCUMENT_CHARS));
            var maxNodes = Math.max(1, Math.min(Number(nodeLimit) || 1, 12000));
            var parts = [];
            var chars = 0;
            var nodes = 0;
            var truncated = false;
            var deadline = Number(sharedDeadline) || (Date.now() + 500);
            if (!root) return { text: '', truncated: false, nodes: 0 };
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            var item;
            while ((item = walker.nextNode())) {
              nodes++;
              if (nodes > maxNodes || (nodes % 64 === 0 && Date.now() > deadline)) {
                truncated = true;
                break;
              }
              var parent = item.parentElement;
              if (!parent || !visible(parent)) continue;
              var tag = String(parent.tagName || '').toLowerCase();
              if (['script','style','noscript','template','svg','canvas','iframe'].includes(tag)) continue;
              var remaining = limit - chars;
              if (remaining <= 0) {
                truncated = true;
                break;
              }
              var text = cleanInline(item.nodeValue, Math.min(remaining, 2000));
              if (!text) continue;
              parts.push(text);
              chars += text.length + 1;
            }
            return {
              text: cleanBlock(parts.join('\n'), limit),
              truncated: truncated,
              nodes: Math.min(nodes, maxNodes)
            };
          }
          function visibleText(root, maxChars, nodeLimit, sharedDeadline) {
            return collectVisibleText(root, maxChars, nodeLimit, sharedDeadline).text;
          }
          function inferredRole(element) {
            var explicit = cleanInline(element.getAttribute('role'), 48);
            if (explicit) return explicit;
            var tag = String(element.tagName || '').toLowerCase();
            var type = String(element.getAttribute('type') || '').toLowerCase();
            if (tag === 'a' && element.hasAttribute('href')) return 'link';
            if (tag === 'button' || ['button','submit','reset','image'].includes(type)) return 'button';
            if (tag === 'select') return element.multiple ? 'listbox' : 'combobox';
            if (tag === 'textarea') return 'textbox';
            if (tag === 'input') {
              if (type === 'checkbox') return 'checkbox';
              if (type === 'radio') return 'radio';
              if (type === 'range') return 'slider';
              return 'textbox';
            }
            if (tag === 'summary') return 'button';
            return null;
          }
          function accessibleName(element, sharedDeadline, allowOwnText) {
            var direct = cleanInline(element.getAttribute('aria-label'), 160);
            if (direct) return direct;
            var labelledBy = cleanInline(element.getAttribute('aria-labelledby'), 240);
            if (labelledBy) {
              var labels = [];
              labelledBy.split(/[\t\r\n ]+/).slice(0, 8).forEach(function(id) {
                var item = document.getElementById(id);
                if (item) labels.push(visibleText(item, 160, 200, sharedDeadline));
              });
              var joined = cleanInline(labels.join(' '), 160);
              if (joined) return joined;
            }
            if (element.labels && element.labels.length) {
              var labelText = [];
              for (var labelIndex = 0; labelIndex < element.labels.length && labelIndex < 4; labelIndex++) {
                labelText.push(visibleText(element.labels[labelIndex], 160, 300, sharedDeadline));
              }
              var labelled = cleanInline(labelText.join(' '), 160);
              if (labelled) return labelled;
            }
            var fallback = cleanInline(
              element.getAttribute('alt') || element.getAttribute('title') ||
              element.getAttribute('placeholder'),
              160
            );
            if (fallback) return fallback;
            return allowOwnText ? visibleText(element, 160, 300, sharedDeadline) : null;
          }
          function describe(element, sharedDeadline) {
            var rect = element.getBoundingClientRect();
            return {
              selector: selectorFor(element),
              tag: boundedString((element.tagName || '').toLowerCase(), 32),
              role: inferredRole(element),
              text: visibleText(element, 160, 400, sharedDeadline),
              aria_label: cleanInline(element.getAttribute('aria-label'), 100),
              placeholder: cleanInline(element.getAttribute('placeholder'), 100),
              href: absoluteUrl(element.getAttribute('href')),
              type: cleanInline(element.getAttribute('type'), 32) || null,
              bounds: {
                x: Math.round(rect.left), y: Math.round(rect.top),
                width: Math.round(rect.width), height: Math.round(rect.height)
              }
            };
          }
          function semanticDescribe(element, ref, sharedDeadline) {
            var description = describe(element, sharedDeadline);
            var exposesValue = valueBearing(element);
            description.ref = ref;
            description.text = exposesValue ? null : description.text;
            description.name = accessibleName(element, sharedDeadline, !exposesValue) || null;
            description.disabled = !enabled(element);
            description.editable = editable(element);
            description.focused = document.activeElement === element;
            description.checked = ('checked' in element) ? !!element.checked : null;
            description.selected = ('selected' in element) ? !!element.selected : null;
            description.expanded = element.hasAttribute('aria-expanded') ?
              element.getAttribute('aria-expanded') === 'true' : null;
            description.pressed = element.hasAttribute('aria-pressed') ?
              element.getAttribute('aria-pressed') === 'true' : null;
            return description;
          }
          function installObservationRefs(elements, documentEpoch) {
            var previous = window[REF_STATE_KEY];
            var sameDocument = previous && previous.documentEpoch === documentEpoch;
            var generation = sameDocument && Number.isFinite(previous.generation) ? previous.generation + 1 : 1;
            var nextRef = sameDocument && Number.isFinite(previous.nextRef) ? previous.nextRef : 1;
            var refs = {};
            var labels = [];
            elements.slice(0, MAX_OBSERVED_ELEMENTS).forEach(function(element) {
              if (nextRef > 999999999) nextRef = 1;
              var label = '@e' + String(nextRef++);
              refs[label] = element;
              labels.push(label);
            });
            window[REF_STATE_KEY] = {
              documentEpoch: documentEpoch,
              generation: generation,
              nextRef: nextRef,
              refs: refs
            };
            return { generation: generation, labels: labels };
          }
          function resolveTarget(selector, ref, x, y, documentEpoch) {
            var target = null;
            if (ref) {
              var state = window[REF_STATE_KEY];
              var match = /^@e([1-9][0-9]{0,8})$/.exec(ref);
              if (!state || state.documentEpoch !== documentEpoch || !match || !state.refs) {
                throw new Error('STALE_ELEMENT_REF');
              }
              target = state.refs[ref];
              if (!target || !target.isConnected) throw new Error('STALE_ELEMENT_REF');
            } else if (selector) {
              target = document.querySelector(selector);
            } else if (Number.isFinite(x) && Number.isFinite(y)) {
              target = document.elementFromPoint(x, y);
            }
            if (!target) throw new Error('TARGET_NOT_FOUND');
            return target;
          }
          function markdownEscape(value) {
            return boundedString(value, 4000).replace(/([\\`*_[\]<>])/g, '\\${'$'}1');
          }
          function markdownState() {
            return {
              parts: [], remainingNodes: 8000, remainingChars: MAX_DOCUMENT_CHARS,
              visited: 0, deadline: Date.now() + 750, truncated: false
            };
          }
          function consumeNode(state) {
            state.visited++;
            state.remainingNodes--;
            if (state.remainingNodes < 0 || (state.visited % 64 === 0 && Date.now() > state.deadline)) {
              state.truncated = true;
              return false;
            }
            return true;
          }
          function emit(state, value) {
            if (state.remainingChars <= 0) {
              state.truncated = true;
              return;
            }
            var text = String(value || '');
            if (text.length > state.remainingChars) {
              text = text.slice(0, state.remainingChars);
              state.truncated = true;
            }
            state.parts.push(text);
            state.remainingChars -= text.length;
          }
          function renderTable(table, state) {
            var output = [];
            var rows = table.rows || [];
            for (var rowIndex = 0; rowIndex < rows.length && rowIndex < 60; rowIndex++) {
              if (!consumeNode(state)) break;
              var row = [];
              var cells = rows[rowIndex].cells || [];
              for (var cellIndex = 0; cellIndex < cells.length && cellIndex < 12; cellIndex++) {
                if (state.truncated || Date.now() > state.deadline) {
                  state.truncated = true;
                  break;
                }
                row.push(visibleText(cells[cellIndex], 300, 200, state.deadline).replace(/\|/g, '\\|'));
              }
              if (row.length) output.push(row);
            }
            if (!output.length) return;
            var width = Math.max.apply(null, output.map(function(row) { return row.length; }));
            output.forEach(function(row) { while (row.length < width) row.push(''); });
            emit(state, '\n\n| ' + output[0].join(' | ') + ' |\n');
            emit(state, '| ' + output[0].map(function() { return '---'; }).join(' | ') + ' |\n');
            for (var index = 1; index < output.length; index++) {
              emit(state, '| ' + output[index].join(' | ') + ' |\n');
            }
            emit(state, '\n');
          }
          function emitChildren(node, depth, state) {
            for (var index = 0; index < node.childNodes.length; index++) {
              if (state.truncated) break;
              emitMarkdown(node.childNodes[index], depth + 1, state);
            }
          }
          function emitMarkdown(node, depth, state) {
            if (!node || state.truncated || depth > 60 || !consumeNode(state)) return;
            if (node.nodeType === Node.TEXT_NODE) {
              var text = cleanInline(node.nodeValue, 2000);
              if (text) emit(state, markdownEscape(text) + ' ');
              return;
            }
            if (node.nodeType !== Node.ELEMENT_NODE || !visible(node)) return;
            var tag = String(node.tagName || '').toLowerCase();
            if (['script','style','noscript','template','svg','canvas','iframe','nav','form','button','input','textarea','select'].includes(tag)) return;
            if (/^h[1-6]$/.test(tag)) {
              emit(state, '\n\n' + '#'.repeat(Number(tag.substring(1))) + ' ');
              emit(state, markdownEscape(visibleText(node, 2000, 500, state.deadline)) + '\n\n');
              return;
            }
            if (tag === 'br') { emit(state, '\n'); return; }
            if (tag === 'hr') { emit(state, '\n\n---\n\n'); return; }
            if (tag === 'pre') {
              var pre = visibleText(node, 6000, 1200, state.deadline).replace(/```/g, '``\\`');
              if (pre) emit(state, '\n\n```\n' + pre + '\n```\n\n');
              return;
            }
            if (tag === 'code') {
              emit(state, '`' + visibleText(node, 1000, 300, state.deadline).replace(/`/g, '\\`') + '`');
              return;
            }
            if (tag === 'blockquote') {
              var quote = visibleText(node, 5000, 1200, state.deadline);
              if (quote) emit(state, '\n\n' + quote.split('\n').map(function(line) { return '> ' + line; }).join('\n') + '\n\n');
              return;
            }
            if (tag === 'table') { renderTable(node, state); return; }
            if (tag === 'a') {
              var label = visibleText(node, 600, 300, state.deadline) || cleanInline(node.getAttribute('aria-label'), 160);
              var href = absoluteUrl(node.getAttribute('href'));
              if (label) emit(state, href ? '[' + markdownEscape(label) + '](' + href + ')' : markdownEscape(label));
              return;
            }
            if (tag === 'img') {
              var alt = cleanInline(node.getAttribute('alt'), 200);
              if (alt) emit(state, '[图片：' + markdownEscape(alt) + ']');
              return;
            }
            if (tag === 'ul' || tag === 'ol') {
              emit(state, '\n\n');
              var number = 0;
              for (var itemIndex = 0; itemIndex < node.children.length && itemIndex < 200; itemIndex++) {
                if (state.truncated || Date.now() > state.deadline) {
                  state.truncated = true;
                  break;
                }
                var item = node.children[itemIndex];
                if (String(item.tagName || '').toLowerCase() !== 'li' || !visible(item)) continue;
                number++;
                emit(state, tag === 'ol' ? String(number) + '. ' : '- ');
                emitChildren(item, depth + 1, state);
                emit(state, '\n');
              }
              emit(state, '\n');
              return;
            }
            var isBlock = ['p','div','main','article','section','header','footer','aside','figure','figcaption','details','summary','dl','dt','dd'].includes(tag);
            if (isBlock) emit(state, '\n\n');
            emitChildren(node, depth, state);
            if (isBlock) emit(state, '\n\n');
          }
          function readableTarget() {
            var selectors = ['article','main','[role="main"]','.article','.post','.entry-content','.content'];
            var candidates = [];
            var seen = new Set();
            var deadline = Date.now() + 300;
            for (var selectorIndex = 0; selectorIndex < selectors.length && Date.now() <= deadline; selectorIndex++) {
              var matches;
              try { matches = document.querySelectorAll(selectors[selectorIndex]); } catch (_) { continue; }
              for (var index = 0; index < matches.length && index < 40 && candidates.length < 80; index++) {
                var item = matches[index];
                if (visible(item) && !seen.has(item)) {
                  seen.add(item);
                  candidates.push(item);
                }
              }
            }
            var best = null;
            var bestScore = -1;
            for (var candidateIndex = 0; candidateIndex < candidates.length && Date.now() <= deadline; candidateIndex++) {
              var score = visibleText(candidates[candidateIndex], 20000, 1000, deadline).length;
              if (score > bestScore) {
                best = candidates[candidateIndex];
                bestScore = score;
              }
            }
            return best || document.body || document.documentElement;
          }
          try {
            var value = (function() {
              $body
            })();
            return JSON.stringify({ ok: true, value: value === undefined ? null : value });
          } catch (error) {
            return JSON.stringify({
              ok: false,
              error: cleanInline(error && error.message ? error.message : 'SCRIPT_FAILED', 160)
            });
          }
        })();
        """.trimIndent()

    fun readable(offset: Int, maxChars: Int): String =
        """
        var target = readableTarget();
        if (!target || !visible(target)) throw new Error('TARGET_NOT_VISIBLE');
        var state = markdownState();
        emitMarkdown(target, 0, state);
        var markdown = cleanBlock(state.parts.join(''), MAX_DOCUMENT_CHARS);
        var total = markdown.length;
        var start = Math.min($offset, total);
        var end = Math.min(start + $maxChars, total);
        return {
          text: markdown.slice(start, end),
          text_length: total,
          returned_chars: end - start,
          offset: start,
          next_offset: end < total ? end : null,
          truncated: end < total || state.truncated,
          source_truncated: state.truncated,
          visited_nodes: state.visited,
          selector_used: selectorFor(target),
          language: cleanInline(document.documentElement.lang, 32) || null,
          canonical_url: (function() {
            var item = document.querySelector('link[rel="canonical"]');
            return item ? absoluteUrl(item.getAttribute('href')) : null;
          })()
        };
        """.trimIndent()

    fun text(selector: String?, offset: Int, maxChars: Int): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        return """
        var selector = $selectorLiteral;
        var target = null;
        if (selector) {
          var matches = document.querySelectorAll(selector);
          for (var index = 0; index < matches.length && index < 2000; index++) {
            if (visible(matches[index])) { target = matches[index]; break; }
          }
        } else {
          target = document.body || document.documentElement;
        }
        if (!target || !visible(target)) throw new Error('TARGET_NOT_VISIBLE');
        var collected = collectVisibleText(target, MAX_DOCUMENT_CHARS, 12000);
        var value = collected.text;
        var total = value.length;
        var start = Math.min($offset, total);
        var end = Math.min(start + $maxChars, total);
        return {
          text: value.slice(start, end),
          text_length: total,
          returned_chars: end - start,
          offset: start,
          next_offset: end < total ? end : null,
          truncated: end < total || collected.truncated,
          source_truncated: collected.truncated,
          visited_nodes: collected.nodes,
          selector_used: selector || selectorFor(target)
        };
        """.trimIndent()
    }

    fun findElements(selector: String?): String {
        val selectorLiteral = JSONObject.quote(
            selector ?: "a,button,input,textarea,select,[role=\"button\"],[role=\"link\"],[contenteditable=\"true\"],[tabindex]"
        )
        return """
        var selector = $selectorLiteral;
        var matches = document.querySelectorAll(selector);
        var elements = [];
        var scanned = 0;
        var deadline = Date.now() + 500;
        for (var index = 0; index < matches.length && index < 3000 && elements.length < 16 && Date.now() <= deadline; index++) {
          scanned++;
          elements.push(describe(matches[index], deadline));
        }
        return {
          selector_used: selector,
          element_count: elements.length,
          scanned_elements: scanned,
          truncated: scanned < matches.length,
          elements: elements
        };
        """.trimIndent()
    }

    fun observe(documentEpoch: Long): String =
        """
        var selector = 'a[href],button,input:not([type="hidden"]),textarea,select,summary,' +
          '[role="button"],[role="link"],[role="checkbox"],[role="radio"],[role="combobox"],' +
          '[role="textbox"],[contenteditable="true"],[tabindex]';
        var matches = document.querySelectorAll(selector);
        var referenced = [];
        var scanned = 0;
        var deadline = Date.now() + 600;
        for (var index = 0; index < matches.length && index < 5000 &&
             referenced.length < MAX_OBSERVED_ELEMENTS && Date.now() <= deadline; index++) {
          scanned++;
          var element = matches[index];
          if (!visible(element) || referenced.includes(element)) continue;
          referenced.push(element);
        }
        var installed = installObservationRefs(referenced, $documentEpoch);
        var elements = referenced.map(function(element, index) {
          return semanticDescribe(element, installed.labels[index], deadline);
        });
        return {
          document_epoch: $documentEpoch,
          observation_id: installed.generation,
          element_count: elements.length,
          scanned_elements: scanned,
          truncated: scanned < matches.length,
          refs_invalidated_by: ['observe','navigation','reset','document_replacement'],
          elements: elements
        };
        """.trimIndent()

    fun click(selector: String?, ref: String?, x: Int?, y: Int?, documentEpoch: Long): String =
        targeted(selector, ref, x, y, documentEpoch) +
            """
            target.scrollIntoView({ block: 'center', inline: 'center' });
            var rect = target.getBoundingClientRect();
            var cx = rect.left + rect.width / 2;
            var cy = rect.top + rect.height / 2;
            ['mousemove','mouseover','mousedown','mouseup'].forEach(function(kind) {
              target.dispatchEvent(new MouseEvent(kind, { bubbles: true, cancelable: true, clientX: cx, clientY: cy }));
            });
            target.click();
            return { matched_element: describe(target) };
            """.trimIndent()

    fun type(
        selector: String?,
        ref: String?,
        x: Int?,
        y: Int?,
        text: String,
        submit: Boolean,
        documentEpoch: Long,
    ): String =
        targeted(selector, ref, x, y, documentEpoch) +
            """
            if (!editable(target)) throw new Error('TARGET_NOT_EDITABLE');
            target.scrollIntoView({ block: 'center', inline: 'center' });
            target.focus();
            var value = ${JSONObject.quote(text)};
            if (target.isContentEditable) {
              target.textContent = value;
            } else {
              var prototype = target.tagName.toLowerCase() === 'textarea' ?
                window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(prototype, 'value');
              if (setter && setter.set) setter.set.call(target, value); else target.value = value;
            }
            target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: null }));
            target.dispatchEvent(new Event('change', { bubbles: true }));
            if ($submit) {
              var form = target.form || target.closest('form');
              if (form && form.requestSubmit) form.requestSubmit();
              else target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
            }
            return { matched_element: describe(target), typed_chars: value.length, submitted: $submit };
            """.trimIndent()

    fun hover(selector: String?, ref: String?, x: Int?, y: Int?, documentEpoch: Long): String =
        targeted(selector, ref, x, y, documentEpoch) +
            """
            if (!visible(target)) throw new Error('TARGET_NOT_VISIBLE');
            target.scrollIntoView({ block: 'center', inline: 'center' });
            var rect = target.getBoundingClientRect();
            var cx = rect.left + rect.width / 2;
            var cy = rect.top + rect.height / 2;
            if (window.PointerEvent) {
              ['pointerover','pointerenter','pointermove'].forEach(function(kind) {
                target.dispatchEvent(new PointerEvent(kind, {
                  bubbles: kind !== 'pointerenter', cancelable: true,
                  clientX: cx, clientY: cy, pointerType: 'mouse'
                }));
              });
            }
            ['mouseover','mouseenter','mousemove'].forEach(function(kind) {
              target.dispatchEvent(new MouseEvent(kind, {
                bubbles: kind !== 'mouseenter', cancelable: true, clientX: cx, clientY: cy
              }));
            });
            return { matched_element: describe(target), strategy: 'synthetic_pointer_events' };
            """.trimIndent()

    fun select(
        selector: String?,
        ref: String?,
        x: Int?,
        y: Int?,
        values: List<String>,
        documentEpoch: Long,
    ): String =
        targeted(selector, ref, x, y, documentEpoch) +
            """
            if (!(target instanceof HTMLSelectElement)) throw new Error('TARGET_NOT_SELECT');
            if (!enabled(target)) throw new Error('TARGET_DISABLED');
            var requested = ${org.json.JSONArray(values)};
            if (!target.multiple && requested.length > 1) throw new Error('TARGET_NOT_MULTI_SELECT');
            var options = Array.from(target.options);
            var available = new Set(options.map(function(option) { return String(option.value); }));
            if (requested.some(function(value) { return !available.has(String(value)); })) {
              throw new Error('SELECT_VALUE_NOT_FOUND');
            }
            var found = new Set();
            options.forEach(function(option) {
              var selected = requested.includes(String(option.value));
              option.selected = selected;
              if (selected) found.add(String(option.value));
            });
            target.dispatchEvent(new Event('input', { bubbles: true }));
            target.dispatchEvent(new Event('change', { bubbles: true }));
            return {
              matched_element: describe(target),
              selected_values: Array.from(found),
              multiple: !!target.multiple
            };
            """.trimIndent()

    fun press(
        selector: String?,
        ref: String?,
        x: Int?,
        y: Int?,
        key: String,
        documentEpoch: Long,
    ): String =
        targetedOptional(selector, ref, x, y, documentEpoch) +
            """
            target = target || document.activeElement || document.body;
            if (!target) throw new Error('TARGET_NOT_FOUND');
            if (target.focus) target.focus();
            var spec = ${JSONObject.quote(key)};
            var ctrl = spec === 'Ctrl+A';
            var shift = spec === 'Shift+Tab';
            var keyName = ctrl ? 'a' : (shift ? 'Tab' : spec);
            var eventKey = keyName === 'Space' ? ' ' : keyName;
            var options = {
              key: eventKey, code: keyName === 'Space' ? 'Space' : keyName,
              bubbles: true, cancelable: true, ctrlKey: ctrl, shiftKey: shift
            };
            var accepted = target.dispatchEvent(new KeyboardEvent('keydown', options));
            var strategy = 'synthetic_key_events';
            if (accepted && ctrl && (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) {
              target.setSelectionRange(0, String(target.value || '').length);
              strategy = 'select_all';
            } else if (accepted && ctrl && target.isContentEditable) {
              var selection = window.getSelection();
              var range = document.createRange();
              range.selectNodeContents(target);
              selection.removeAllRanges();
              selection.addRange(range);
              strategy = 'select_all';
            } else if (accepted && keyName === 'Tab') {
              var focusable = Array.from(document.querySelectorAll(
                'a[href],button,input,textarea,select,[contenteditable="true"],[tabindex]'
              )).filter(function(item) { return enabled(item) && Number(item.tabIndex) >= 0; });
              var current = focusable.indexOf(target);
              var delta = shift ? -1 : 1;
              var next = focusable.length ?
                (current < 0 ? (shift ? focusable.length - 1 : 0) :
                  (current + delta + focusable.length) % focusable.length) : -1;
              if (next >= 0) focusable[next].focus();
              strategy = 'focus_traversal';
            } else if (accepted && keyName === 'Enter') {
              var form = target.form || (target.closest ? target.closest('form') : null);
              if (target instanceof HTMLInputElement && form && form.requestSubmit) form.requestSubmit();
              else if (target.click && (inferredRole(target) === 'button' || inferredRole(target) === 'link')) target.click();
              strategy = 'enter_activation';
            } else if (accepted && keyName === 'Space' && target.click &&
                       ['button','checkbox','radio'].includes(inferredRole(target))) {
              target.click();
              strategy = 'space_activation';
            }
            target.dispatchEvent(new KeyboardEvent('keyup', options));
            return {
              matched_element: describe(target),
              focused_element: document.activeElement instanceof Element ? describe(document.activeElement) : null,
              key: spec,
              strategy: strategy
            };
            """.trimIndent()

    fun scroll(selector: String?, direction: String, amount: Int): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        return """
        var selector = $selectorLiteral;
        var target = selector ? document.querySelector(selector) : (document.scrollingElement || document.documentElement);
        if (!target || (selector && !visible(target))) throw new Error('TARGET_NOT_VISIBLE');
        var delta = ${if (direction == "up") -amount else amount};
        var documentTarget = target === document.scrollingElement || target === document.documentElement || target === document.body;
        var before = documentTarget ? window.scrollY : target.scrollTop;
        if (documentTarget) window.scrollBy(0, delta); else target.scrollBy(0, delta);
        var after = documentTarget ? window.scrollY : target.scrollTop;
        return {
          selector_used: selector || selectorFor(target),
          direction: ${JSONObject.quote(direction)}, amount: $amount, before: before, after: after
        };
        """.trimIndent()
    }

    fun pageInfo(): String =
        """
        var canonical = document.querySelector('link[rel="canonical"]');
        return {
          viewport_width: window.innerWidth,
          viewport_height: window.innerHeight,
          content_width: Math.min(200000, Math.max(document.body ? document.body.scrollWidth : 0, document.documentElement.scrollWidth)),
          content_height: Math.min(200000, Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement.scrollHeight)),
          scroll_x: window.scrollX || 0,
          scroll_y: window.scrollY || 0,
          language: cleanInline(document.documentElement.lang, 32) || null,
          canonical_url: canonical ? absoluteUrl(canonical.getAttribute('href')) : null
        };
        """.trimIndent()

    fun highlightHelpTarget(selector: String): String =
        """
        var previous = window[HELP_TARGET_KEY];
        if (previous && previous.element && previous.element.isConnected) {
          previous.element.style.setProperty('outline', previous.outline, previous.outlinePriority);
          previous.element.style.setProperty('outline-offset', previous.offset, previous.offsetPriority);
        }
        var target = document.querySelector(${JSONObject.quote(selector)});
        if (!target || !visible(target)) throw new Error('TARGET_NOT_VISIBLE');
        window[HELP_TARGET_KEY] = {
          element: target,
          outline: target.style.getPropertyValue('outline'),
          outlinePriority: target.style.getPropertyPriority('outline'),
          offset: target.style.getPropertyValue('outline-offset'),
          offsetPriority: target.style.getPropertyPriority('outline-offset')
        };
        target.style.setProperty('outline', '3px solid #ff9800', 'important');
        target.style.setProperty('outline-offset', '3px', 'important');
        target.scrollIntoView({ block: 'center', inline: 'center' });
        return { resolved_target: true, matched_element: describe(target) };
        """.trimIndent()

    fun clearHelpTarget(): String =
        """
        var previous = window[HELP_TARGET_KEY];
        if (previous && previous.element && previous.element.isConnected) {
          previous.element.style.setProperty('outline', previous.outline, previous.outlinePriority);
          previous.element.style.setProperty('outline-offset', previous.offset, previous.offsetPriority);
        }
        delete window[HELP_TARGET_KEY];
        return { cleared: true };
        """.trimIndent()

    fun completionState(urlContains: String?, selectorExists: String?, match: String): String {
        val urlLiteral = urlContains?.let(JSONObject::quote) ?: "null"
        val selectorLiteral = selectorExists?.let(JSONObject::quote) ?: "null"
        return """
        var urlContains = $urlLiteral;
        var selectorExists = $selectorLiteral;
        var signals = [];
        if (urlContains) signals.push(String(window.location.href || '').includes(urlContains));
        if (selectorExists) signals.push(!!document.querySelector(selectorExists));
        return {
          matched: signals.length > 0 && ${if (match == "all") "signals.every(Boolean)" else "signals.some(Boolean)"},
          url_matched: urlContains ? signals[0] : null,
          selector_matched: selectorExists ? signals[signals.length - 1] : null
        };
        """.trimIndent()
    }

    fun selectorState(selector: String): String =
        """
        var matches = document.querySelectorAll(${JSONObject.quote(selector)});
        var target = null;
        for (var index = 0; index < matches.length && index < 2000; index++) {
          if (visible(matches[index])) { target = matches[index]; break; }
        }
        return { found: !!target, visible: !!target, enabled: target ? enabled(target) : false };
        """.trimIndent()

    private fun targeted(
        selector: String?,
        ref: String?,
        x: Int?,
        y: Int?,
        documentEpoch: Long,
    ): String = targetedOptional(selector, ref, x, y, documentEpoch) +
        "if (!target) throw new Error('TARGET_NOT_FOUND');\n"

    private fun targetedOptional(
        selector: String?,
        ref: String?,
        x: Int?,
        y: Int?,
        documentEpoch: Long,
    ): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        val refLiteral = ref?.let(JSONObject::quote) ?: "null"
        val xLiteral = x?.toString() ?: "null"
        val yLiteral = y?.toString() ?: "null"
        val hasTarget = selector != null || ref != null || x != null || y != null
        return if (hasTarget) {
            "var target = resolveTarget($selectorLiteral, $refLiteral, $xLiteral, $yLiteral, $documentEpoch);\n"
        } else {
            "var target = null;\n"
        }
    }
}
