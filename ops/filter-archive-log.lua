math.randomseed(os.time())

local function uuid()
    local template = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"
    return string.gsub(template, "[xy]", function(character)
        local value = character == "x" and math.random(0, 15) or math.random(8, 11)
        return string.format("%x", value)
    end)
end

function archive_log(tag, timestamp, record)
    local level = record["level"]
    if level ~= "WARN" and level ~= "ERROR" then
        return -1, timestamp, record
    end
    local logger = record["logger_name"] or "unknown"
    local message = record["message"] or "structured log message"
    return 1, timestamp, {
        logId = uuid(),
        occurredAt = os.date("!%Y-%m-%dT%H:%M:%SZ", math.floor(timestamp)),
        level = level,
        logger = string.sub(logger, 1, 200),
        message = string.sub(message, 1, 2000),
        service = "blindway-backend"
    }
end
