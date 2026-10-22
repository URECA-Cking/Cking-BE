-- KEYS[1] = auth:login-code:{Code SHA-256}, 값은 "{memberId}:{브라우저 값 SHA-256}"
-- ARGV[1] = 교환을 요청한 브라우저 값의 SHA-256
-- 브라우저 값이 같을 때만 지우고 회원 ID를 돌려준다. 다르면 Code를 그대로 둔다.
local value = redis.call('GET', KEYS[1])
if not value then
    return nil
end
local separator = string.find(value, ':', 1, true)
if not separator or string.sub(value, separator + 1) ~= ARGV[1] then
    return nil
end
redis.call('DEL', KEYS[1])
return string.sub(value, 1, separator - 1)
