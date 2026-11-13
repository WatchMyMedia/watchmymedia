import Foundation
import WMMModel

/// The guide boards' fictional listings (design/mockups/ios/LiveGuide and
/// friends, PLAN-LIVE-TV-GUIDE.md §6.4), for `-fakesLiveGuide`. The boards
/// stop the clock at 8:12 PM, in their 8:00 half hour. Here the half hour now
/// is in plays the boards' 8:00, so what is on now is what the boards show at
/// any minute; only how far in it is differs (0–29 minutes, where the boards
/// are 12). Each channel's evening repeats until listings end two days later.
public struct FakeLiveGuide: Sendable {
    /// The boards' `listings` and `picture` Tweaks.
    public enum Pictures: Sendable { case series, tmdb, missing }
    /// The extras boards' `reminder` Tweak: Conference Football on 4.1 at
    /// 9:00 PM with no reminder, with one, moved to 9:30 PM, or gone from the listings.
    public enum Conference: String, Sendable { case none, set, moved, gone }

    public let anchor: Date
    public let stale: Bool
    public let pictures: Pictures
    public let conference: Conference
    /// The extras boards' `filter` Tweak: Sam starts with five favourites, or none.
    public let favourites: Bool
    /// Favourites, search and reminders (API level 15); false keeps the server at G5's level 13.
    public let extras: Bool
    /// A reminder whose alert comes `soonAfter` seconds after launch (20 by
    /// default) with the default lead time, to watch one arrive.
    public let soon: Date?

    public init(now: Date = .now, stale: Bool = false, pictures: Pictures = .series, conference: Conference = .none,
                favourites: Bool = true, extras: Bool = true, soon: Bool = false, soonAfter: TimeInterval = 20,
                calendar: Calendar = .current) {
        self.soon = soon ? now.addingTimeInterval(Double(LiveReminderLead.standard) * 60 + soonAfter) : nil
        // The boards' 7:00 PM: an hour before the half hour now is in, found
        // by subtracting so the hour repeated when clocks go back stays put.
        let c = calendar.dateComponents([.minute, .second, .nanosecond], from: now)
        let intoHalfHour = Double((c.minute ?? 0) % 30 * 60 + (c.second ?? 0)) + Double(c.nanosecond ?? 0) / 1e9
        anchor = now.addingTimeInterval(-intoHalfHour - 3600)
        self.stale = stale
        self.pictures = pictures
        self.conference = conference
        self.favourites = favourites
        self.extras = extras
    }

    /// The board's minutes from 7:00 PM.
    public func at(_ minutes: Int) -> Date { anchor.addingTimeInterval(Double(minutes) * 60) }

    /// The schedule with the `reminder` Tweak's 4.1.
    var schedule: [Entry] {
        guard conference == .moved || conference == .gone else { return Self.schedule }
        return Self.schedule.map { entry in
            guard entry.number == "4.1", var rows = entry.rows else { return entry }
            let football = rows[3], news = rows[4]
            rows.removeLast(2)
            rows += conference == .moved
                ? [news, football]
                : [Self.row(120, "Northfield News", "Special coverage", "News", "LH", "Special coverage from the Northfield newsroom, with reports from across the valley.")]
            return Entry(number: entry.number, name: entry.name, logo: entry.logo, rows: rows)
        }
    }

    /// The channel list, with what is on at `date` and what follows.
    public func channels(at date: Date) -> [LiveChannel] {
        schedule.map { entry in
            var channel = entry.channel(pictures: pictures)
            if let rows = entry.rows {
                let all = programmes(entry, rows, from: date.addingTimeInterval(-1), to: date.addingTimeInterval(6 * 3600))
                channel.now = all.first { $0.isOn(at: date) }
                channel.next = all.first { $0.start >= (channel.now?.end ?? date) }
            }
            return channel
        }
    }

    public var state: LiveGuideState {
        LiveGuideState(enabled: true, stale: stale,
                       updatedAt: stale ? anchor.addingTimeInterval(-49 * 3600 + 40 * 60) : anchor.addingTimeInterval(-3 * 3600),
                       listedThrough: listedThrough)
    }

    /// `-fakesLiveGuide`, with `-fakesLiveGuideStale`, `-fakesLiveGuideTMDB` or
    /// `-fakesLiveGuideNoPictures`; `-fakesLiveReminder set|moved|gone` and
    /// `-fakesLiveNoFavourites` for the extras boards' Tweaks,
    /// `-fakesLiveGuideNoExtras` for a server before them (level 13), and
    /// `-fakesLiveReminderSoon` for a reminder that comes 20 s after launch, or
    /// as many seconds as the number after it (`-fakesLiveReminderSoon 8`), so
    /// a UI test waits only as long as its screen needs. Nil without it.
    public static func from(_ arguments: [String], now: Date = .now) -> FakeLiveGuide? {
        guard arguments.contains("-fakesLiveRecordings") || arguments.contains(where: { $0.hasPrefix("-fakesLiveGuide") }) else { return nil }
        let pictures: Pictures = arguments.contains("-fakesLiveGuideNoPictures") ? .missing : arguments.contains("-fakesLiveGuideTMDB") ? .tmdb : .series
        var conference = Conference.none
        if let i = arguments.firstIndex(of: "-fakesLiveReminder"), arguments.indices.contains(i + 1) {
            conference = Conference(rawValue: arguments[i + 1]) ?? .none
        }
        var soonAfter: TimeInterval = 20
        if let i = arguments.firstIndex(of: "-fakesLiveReminderSoon"), arguments.indices.contains(i + 1),
           let seconds = TimeInterval(arguments[i + 1]), seconds >= 0 {
            soonAfter = seconds
        }
        return FakeLiveGuide(now: now, stale: arguments.contains("-fakesLiveGuideStale"), pictures: pictures, conference: conference,
                             favourites: !arguments.contains("-fakesLiveNoFavourites"), extras: !arguments.contains("-fakesLiveGuideNoExtras"),
                             soon: arguments.contains("-fakesLiveReminderSoon"), soonAfter: soonAfter)
    }

    /// Every programme from `from` until listings end, by channel.
    func programmes(from: Date) -> [(channel: LiveChannel, programme: LiveProgramme)] {
        schedule.flatMap { entry -> [(channel: LiveChannel, programme: LiveProgramme)] in
            guard let rows = entry.rows else { return [] }
            let channel = entry.channel(pictures: pictures)
            return programmes(entry, rows, from: from, to: listedThrough).map { (channel, $0) }
        }
    }

    /// The first programme called `title` on `number` starting at or after `after`.
    func programme(_ title: String, on number: String, after: Date) -> (channel: LiveChannel, programme: LiveProgramme)? {
        programmes(from: after).filter { $0.channel.number == number && $0.programme.title == title && $0.programme.start >= after }
            .min { $0.programme.start < $1.programme.start }
    }

    /// The extras boards' reminders: today Dollar Dash, Seven Bridges West and
    /// Station 9 (and Conference Football with the Tweak), tomorrow Valley
    /// Bake-Off, Nature's Edge moved and Cabin Builders gone.
    func reminders() -> [LiveReminder] {
        if let soon {
            // This scenario watches one alert arrive. A canned reminder near
            // the half hour could otherwise be due first and take its place.
            return [LiveReminder(id: "rem-soon", channelID: "ch-9.1", number: "9.1", channelName: "PNVL-HD", programmeID: "9.1-soon",
                                 start: soon, end: soon.addingTimeInterval(3600), title: "Pro Hockey", episodeTitle: "Ridgeway Hawks vs. Port Ellis")]
        }
        var out: [LiveReminder] = []
        func add(_ title: String, _ number: String, after: Date, state: LiveReminder.State = .set) {
            guard let found = programme(title, on: number, after: after) else { return }
            var r = LiveReminder(programme: found.programme, channel: found.channel, id: "rem-\(found.programme.id)")
            r.state = state
            out.append(r)
        }
        add("Dollar Dash", "2.1", after: at(90))
        add("Seven Bridges West", "20.1", after: at(105))
        switch conference {
        case .none: break
        case .set: add("Conference Football", "4.1", after: at(120))
        case .moved: add("Conference Football", "4.1", after: at(150), state: .moved)
        case .gone:
            // Kept as it was set: 9:00–10:30 PM, which the listings no longer have.
            out.append(LiveReminder(id: "rem-4.1-gone", channelID: "ch-4.1", number: "4.1", channelName: "NFLD-HD", programmeID: "4.1-\(Int(at(120).timeIntervalSince1970))",
                                    start: at(120), end: at(210), title: "Conference Football", episodeTitle: "Ridgeway at Lakeport", state: .gone))
        }
        add("Station 9", "7.1", after: at(120))
        let tomorrow = anchor.addingTimeInterval(20 * 3600)
        add("Valley Bake-Off", "7.1", after: tomorrow)
        add("Nature’s Edge", "11.1", after: tomorrow, state: .moved)
        add("Cabin Builders", "7.2", after: tomorrow, state: .gone)
        return out.sorted { $0.start < $1.start }
    }

    /// Wednesday 7:00 PM on the boards.
    public var listedThrough: Date { anchor.addingTimeInterval(48 * 3600) }

    public func guide(start: Date, hours: Int) -> LiveGuide {
        let end = start.addingTimeInterval(Double(min(max(hours, 1), 6)) * 3600)
        let channels = schedule.map { entry in
            let channel = entry.channel(pictures: pictures)
            guard let rows = entry.rows else { return LiveGuideChannel(channel: channel, listings: false) }
            return LiveGuideChannel(channel: channel, listings: true, programmes: programmes(entry, rows, from: start, to: end))
        }
        return LiveGuide(start: start, end: end, state: state, channels: channels)
    }

    /// The channel's evening from 6:00 PM, repeated back a day and on to the end of listings.
    private func programmes(_ entry: Entry, _ rows: [Row], from: Date, to: Date) -> [LiveProgramme] {
        let cycle = Double(rows.reduce(0) { $0 + $1.minutes }) * 60
        var start = anchor.addingTimeInterval(-3600)
        while start > from.addingTimeInterval(-cycle) { start -= cycle }
        var out: [LiveProgramme] = []
        while start < min(to, listedThrough) {
            for row in rows {
                let end = start.addingTimeInterval(Double(row.minutes) * 60)
                defer { start = end }
                guard end > from, start < to, start < listedThrough else { continue }
                out.append(row.programme(channel: entry.number, start: start, end: min(end, listedThrough), pictures: pictures))
            }
        }
        return out
    }

    struct Entry: Sendable {
        let number: String
        let name: String
        /// Wordmark, colour and shape, drawn by ``FakeLiveArt``; nil for a channel the feed has no logo for.
        let logo: (String, UInt32, String)?
        /// Nil: the feed doesn't list the channel.
        let rows: [Row]?

        func channel(pictures: Pictures) -> LiveChannel {
            LiveChannel(id: "ch-\(number)", number: number, name: name,
                        logoURL: logo.flatMap { FakeLiveArt.logoURL(word: $0.0, colour: $0.1, shape: $0.2) })
        }
    }

    struct Row: Sendable {
        let minutes: Int
        let title: String
        let line: String
        let categories: String
        let tags: String
        let description: String

        func programme(channel: String, start: Date, end: Date, pictures: Pictures) -> LiveProgramme {
            var p = LiveProgramme(id: "\(channel)-\(Int(start.timeIntervalSince1970))", start: start, end: end, title: title,
                                  isNew: tags.contains("N"), live: tags.contains("L"), hd: tags.contains("H"),
                                  categories: categories.components(separatedBy: " · ").filter { !$0.isEmpty },
                                  description: description)
            let parts = line.components(separatedBy: " · ")
            let numbers = parts.first?.split(separator: " ") ?? []
            if parts.first == "Movie" {
                p.kind = .movie
                p.airDate = parts.count > 1 ? parts[1] : nil
            } else if numbers.count == 2, numbers[1].hasPrefix("E"), let episode = Int(numbers[1].dropFirst()),
                      let season = Int(numbers[0].hasPrefix("S") ? numbers[0].dropFirst() : numbers[0]) {
                p.kind = .episode
                p.season = season
                p.episode = episode
                p.episodeTitle = parts.count > 1 ? parts[1] : nil
                p.seriesKey = "SH-\(title)"
            } else if !line.isEmpty {
                p.kind = .episode
                p.episodeTitle = line
                p.seriesKey = "SH-\(title)"
            } else {
                p.kind = .show
            }
            if pictures != .missing { p.pictureURL = FakeLiveArt.pictureURL(title: title) }
            if pictures == .tmdb, FakeLiveGuide.tmdb.contains(title) { p.stillURL = FakeLiveArt.stillURL(title: title) }
            return p
        }
    }

    /// Programmes the boards' `tmdb` Tweak gives a matched still.
    static let tmdb: Set<String> = ["The Sleuth Files", "Valley Bake-Off", "Station 9", "Inspector Vane", "Coastline Rescue", "Cabin Builders",
                                    "The Iron Coast", "Night of the Lanterns", "The Long Shore", "The Glass Harbor", "Seven Bridges West"]

    private static func row(_ minutes: Int, _ title: String, _ line: String, _ categories: String, _ tags: String, _ description: String) -> Row {
        Row(minutes: minutes, title: title, line: line, categories: categories, tags: tags, description: description)
    }

    // The iPhone LiveGuide board's channels and evening, from 6:00 PM.
    static let schedule: [Entry] = [
        Entry(number: "2.1", name: "HRBR-HD", logo: ("HRBR", 0x7CC4F2, "disc"), rows: [
            row(60, "Harbor News at 6", "", "News", "L", "Local news, weather and the evening commute from the Harbor newsroom."),
            row(60, "Harbor Evening News", "", "News", "", "The day’s headlines from around the bay, with the marine forecast and tomorrow’s tide times."),
            row(30, "Quiz Parlor", "S12 E88", "Series · Game show", "", "Three returning champions face the lightning round, and the winner plays for the parlor’s jackpot."),
            row(30, "Dollar Dash", "S3 E14 · Double or Nothing", "Series · Game show", "N", "Two families race through the aisles with a shopping list, one cart and ninety seconds on the clock."),
            row(60, "Coastline Rescue", "S2 E9 · Breakwater", "Series · Reality", "NH", "A trawler loses power off the point in a gale, and the night crew has one chance to reach it before the tide turns."),
            row(60, "Harbor News at 10", "", "News", "L", "The late news from the Harbor newsroom, with sport and the overnight forecast."),
        ]),
        Entry(number: "2.2", name: "Harbor Weather", logo: ("HWX", 0xF2C14E, "disc"), rows: [
            row(60, "Weather Now", "", "Weather", "", "Radar, the hour-by-hour forecast and conditions on the water, updated through the evening."),
            row(120, "Weather Now", "", "Weather", "", "Radar, the hour-by-hour forecast and conditions on the water, updated through the evening."),
            row(120, "Weather Now", "Overnight", "Weather", "", "The overnight forecast, marine warnings and a first look at tomorrow’s commute."),
        ]),
        Entry(number: "2.3", name: "Retro Reels", logo: ("REEL", 0xE58A6B, "sq"), rows: [
            row(60, "Laugh Lines", "S2 E4 · Snow Day", "Series · Sitcom", "", "The whole office is snowed in, and Dot is sure the vending machine is rationing on purpose."),
            row(60, "Laugh Lines", "S2 E5 · The Wrong Umbrella", "Series · Sitcom", "", "Dot borrows an umbrella from the office stand and spends the day returning it to the wrong people."),
            row(90, "The Iron Coast", "Movie · 1958", "Movie · Adventure", "", "A lighthouse keeper and a stranded smuggler wait out a storm, each sure the other is lying about the wreck. (3.0/4.0)"),
            row(90, "Night of the Lanterns", "Movie · 1961", "Movie · Mystery", "", "On the last night of a seaside festival, a lantern-maker’s apprentice sees something she was never meant to see. (2.5/4.0)"),
        ]),
        Entry(number: "4.1", name: "NFLD-HD", logo: ("NFLD", 0x7FD18B, "dia"), rows: [
            row(60, "Northfield News at 6", "", "News", "L", "Local news, sport and weather for Northfield and the valley towns."),
            row(60, "Market Minute", "", "News · Business", "", "Closing prices, the day’s biggest movers and what the freight numbers say about the week ahead."),
            row(60, "The Sleuth Files", "S2 E4 · The Low Tide", "Series · Mystery · Drama", "NH", "A body on the mudflats at low tide leads Detective Ana Ruiz to a boatyard that closed ten years ago, and to a witness who swears she saw a boat go out that night. Ruiz has until the next tide to find it, and the boatyard’s owner has already called a lawyer."),
            row(90, "Conference Football", "Ridgeway at Lakeport", "Sports · Football", "LH", "The Ridgeway Rams travel to Lakeport for the last game of the regular season, with first place in the conference on the line."),
            row(30, "Northfield News", "", "News", "", "The late headlines and the overnight forecast."),
        ]),
        Entry(number: "4.2", name: "Sleuth Hour", logo: ("SLTH", 0xB69CF2, "bar"), rows: [
            row(60, "Inspector Vane", "S3 E1 · Fog Line", "Series · Mystery", "", "A ferry comes in with one passenger fewer than it left with, and nobody on board will say who."),
            row(60, "Inspector Vane", "S3 E2 · The Ferryman", "Series · Mystery", "", "The ferry’s captain has an alibi for every crossing but one, and Vane has seen him lie before."),
            row(60, "Inspector Vane", "S3 E3 · A Quiet Harbour", "Series · Mystery", "", "Vane goes back to the village where he grew up to find the harbourmaster missing and the whole town telling the same story."),
            row(120, "Case Closed?", "S1 E7", "Series · Documentary", "", "Retired detectives reopen a 1986 disappearance with the original case files and a witness who has never spoken on camera."),
        ]),
        Entry(number: "4.3", name: "Coast Shop", logo: nil, rows: nil),
        Entry(number: "4.4", name: "Night Owl", logo: nil, rows: [
            row(60, "Midnight Mysteries", "", "Series · Mystery", "", "Back-to-back episodes of the old radio-play mysteries, restored and set to new pictures."),
            row(120, "Midnight Mysteries", "", "Series · Mystery", "", "Back-to-back episodes of the old radio-play mysteries, restored and set to new pictures."),
            row(120, "Midnight Mysteries", "", "Series · Mystery", "", "Back-to-back episodes of the old radio-play mysteries, restored and set to new pictures."),
        ]),
        Entry(number: "7.1", name: "LKSD-HD", logo: ("LKSD", 0x5FD4C4, "disc"), rows: [
            row(60, "Lakeside News at 6", "", "News", "L", "Local news, sport and weather from the Lakeside newsroom."),
            row(60, "Lakeside Evening News", "", "News", "", "The evening headlines from the lake towns, with the forecast and the week’s road works."),
            row(60, "Valley Bake-Off", "S6 E10 · Pies", "Series · Cooking", "NH", "The last five bakers have three hours to make a lattice-top fruit pie, and the judges will be checking the bottom crust."),
            row(60, "Station 9", "S4 E12 · The Harbor Fire", "Series · Drama", "NH", "A warehouse fire on the waterfront traps two of the crew, and Captain Reyes has to decide who goes back in."),
            row(60, "Lakeside News at 10", "", "News", "L", "The late news from Lakeside, with sport and the overnight forecast."),
        ]),
        Entry(number: "7.2", name: "Driftwood", logo: ("drift", 0xD9B38C, "bar"), rows: [
            row(60, "Wild Rivers", "S1 E3 · Snowmelt", "Series · Nature", "", "Spring comes late to the high valleys, and the river that feeds three towns runs at twice its usual height."),
            row(60, "Trail Kitchen", "S2 E1 · Camp Bread", "Series · Cooking", "", "Bannock, skillet flatbread and a sourdough starter that has been carried up the same trail for twelve years."),
            row(60, "Cabin Builders", "S1 E5 · The Roof", "Series · Reality", "", "With the first snow a week away, the Haldane brothers race to get a cedar-shake roof on a cabin with no road in."),
            row(120, "The Long Shore", "Movie · 1974", "Movie · Drama", "", "Two sisters drive the length of the coast road to scatter their father’s ashes, and argue about every mile of it. (3.5/4.0)"),
        ]),
        Entry(number: "7.3", name: "Kids Corner", logo: ("KC", 0xF28DB2, "sq"), rows: [
            row(60, "Puzzle Pals", "S1 E8", "Children", "", "The pals sort the toy box by shape, then by colour, then give up and build a castle."),
            row(60, "Robo Rangers", "S2 E11 · The Big Recharge", "Children · Animation", "", "The Rangers’ base loses power on the night of the meteor shower, and Bolt has to fix it without his tools."),
            row(30, "Sprout & Pip", "Rainy Day", "Children", "", "Pip wants to splash in every puddle and Sprout wants to stay dry. They find a way to do both."),
            row(30, "Puzzle Pals", "S1 E9", "Children", "", "The pals build a bridge out of shapes so the duck family can cross the stream."),
            row(120, "Kids Corner Overnight", "", "Children", "", "Gentle cartoons and songs through the night."),
        ]),
        Entry(number: "9.1", name: "PNVL-HD", logo: ("PNVL", 0xF2A65A, "dia"), rows: [
            row(60, "Penvale Tonight", "", "News", "L", "The evening news from Penvale and the river towns."),
            row(60, "Trivia Town", "2026 E88", "Series · Game show", "", "Players from three neighbouring towns answer for a share of the pot and a trophy shaped like a water tower."),
            row(120, "Pro Hockey", "Ridgeway Hawks vs. Port Ellis", "Sports · Hockey", "LH", "The Hawks open a three-game homestand against Port Ellis, two points back in the division with a game in hand."),
            row(60, "Postgame", "", "Sports", "L", "Highlights, interviews and the night’s other scores."),
        ]),
        Entry(number: "11.1", name: "Valley Public", logo: ("VP", 0xA6D96A, "sq"), rows: [
            row(60, "Valley Gardener", "S8 E6 · Bulbs", "Series · Home", "", "What to plant now for a spring that comes all at once, and how deep the tulips really need to go."),
            row(60, "World Desk", "", "News", "", "International news and analysis, with reports from correspondents abroad."),
            row(60, "Nature’s Edge", "2026 E14 · Owls of the Valley", "Series · Nature", "N", "A year with the barn owls of the river meadows, filmed from inside a nest box over three breeding seasons."),
            row(120, "Great Rail Trips", "S2 E3 · The Lake Line", "Series · Travel", "", "A slow train around the lakes, with stops for smoked fish, a ferry crossing and a station that has not changed since 1911."),
        ]),
        Entry(number: "14.1", name: "Canal Lago", logo: ("LAGO", 0xFF8A80, "disc"), rows: [
            row(60, "Noticias Lago", "", "News", "L", "Las noticias del día en la región de los lagos, con el tiempo y los deportes."),
            row(60, "Cocina de Casa", "S3 E12 · Tamales", "Series · Cooking", "", "Rosa prepara tamales de elote con la receta de su abuela y enseña a doblar las hojas."),
            row(60, "Amor en la Costa", "S1 E47", "Series · Drama", "N", "Lucía descubre la verdad sobre la carta que su madre escondió durante veinte años y decide contárselo a Mateo."),
            row(120, "Fútbol de la Liga Valle", "Ridgeway vs. Lakeport", "Sports · Soccer", "L", "Partido en vivo desde el estadio de Lakeport, con el primer puesto de la liga en juego."),
        ]),
        Entry(number: "20.1", name: "Cinema Vault", logo: ("CV", 0xE8C872, "bar"), rows: [
            row(60, "Cartoon Classics", "", "Animation", "", "Restored cartoons from the 1930s and 1940s."),
            row(105, "The Glass Harbor", "Movie · 1949", "Movie · Drama", "", "A harbour pilot who never misses a tide takes a bribe to bring a ship in blind, and the whole town pays for it. (3.5/4.0)"),
            row(135, "Seven Bridges West", "Movie · 1955", "Movie · Western", "", "A surveyor charting a railroad through the canyons finds that every bridge on his map has already been sold. (3.0/4.0)"),
        ]),
    ]
}
