local sessionValue = redis.call('GET', KEYS[1])
if not sessionValue or string.sub(sessionValue, 1, string.len(ARGV[2]) + 1) ~= ARGV[2] .. ':' then
    return nil
end

redis.call('DEL', KEYS[1])
redis.call('SET', KEYS[2], sessionValue, 'PX', ARGV[1])
return sessionValue
