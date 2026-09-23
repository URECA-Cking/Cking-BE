local memberId = redis.call('GET', KEYS[1])
if not memberId then
    return nil
end

redis.call('DEL', KEYS[1])
redis.call('SET', KEYS[2], memberId, 'PX', ARGV[1])
return memberId
