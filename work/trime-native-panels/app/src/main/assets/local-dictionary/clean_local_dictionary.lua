-- SPDX-License-Identifier: GPL-3.0-or-later
-- App-private imported words. Reloads one complete atomic revision, never a network source.
local M = {}
function M.init(env)
  env.path = rime_api.get_user_data_dir() .. '/smart_imported.tsv'
  env.revision = false
  env.words = {}
end
local function refresh(env)
  local file = io.open(env.path, 'rb')
  if not file then env.revision = false; env.words = {}; return end
  local revision = file:read('*l')
  local body_offset = file:seek()
  if revision then revision = revision:gsub('\r$', '') end
  if revision == env.revision then file:close(); return end
  local bytes = file:seek('end') or 0
  if bytes > 8 * 1024 * 1024 or not revision or not revision:match('^# revision ') then
    file:close(); env.words = {}; env.revision = false; return
  end
  file:seek('set', body_offset)
  local words = {}; local count = 0
  for raw_line in file:lines() do
    local line = raw_line:gsub('\r$', '')
    if #line <= 1024 then
      local text, code, weight = line:match('^([^\t]+)\t([^\t]+)\t(%d+)$')
      if text and code and code:match('^[a-z ]+$') then
        code = code:gsub(' ', '')
        words[code] = words[code] or {}
        if #words[code] < 64 then table.insert(words[code], {text = text, weight = tonumber(weight) or 0}) end
        count = count + 1
      end
    end
    if count >= 100000 then break end
  end
  file:close()
  for _, list in pairs(words) do table.sort(list, function(a,b) return a.weight > b.weight end) end
  env.words = words; env.revision = revision
end
function M.func(input, segment, env)
  local context = env.engine.context
  if context:get_option('ascii_mode') or context:get_option('smart_sensitive_input') then return end
  if #input > 160 or not input:match("^[a-z']+$") then return end
  refresh(env)
  for index, item in ipairs(env.words[input:gsub("'", '')] or {}) do
    local candidate = Candidate('clean_imported', segment.start, segment._end, item.text, '用户词库')
    candidate.quality = 99 + math.min(item.weight, 1000000) / 1000000
    yield(candidate)
    if index >= 32 then break end
  end
end
function M.fini(env) env.words = {} end
return M
