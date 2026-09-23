local memberId = redis.call('GET', KEYS[1])
if memberId then
    redis.call('DEL', KEYS[1])
end
return memberId
