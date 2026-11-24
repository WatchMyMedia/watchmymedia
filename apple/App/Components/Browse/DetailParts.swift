import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The top of Show and Movie: the backdrop fading into the ground under the
/// navigation bar, the poster overlapping it, and `info` (title, facts,
/// tags) beside the poster. `topInset` is the safe area the page scrolls
/// under (status and navigation bars), so the poster sits where the mockups put it.
struct DetailHeader<Info: View>: View {
    let title: String
    let backdropURL: URL?
    let posterURL: URL?
    let topInset: CGFloat
    @ViewBuilder var info: () -> Info

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.clear
                .frame(height: topInset + 117)
                .frame(maxWidth: .infinity)
                .overlay { Artwork(url: backdropURL, seed: title, maxPixel: 1400) }
                .overlay {
                    RadialGradient(colors: [Palette.gold.opacity(0.10), .clear], center: UnitPoint(x: 0.75, y: 0.2), startRadius: 0, endRadius: 220)
                }
                .overlay {
                    LinearGradient(
                        stops: [
                            .init(color: Palette.ground.opacity(0.35), location: 0),
                            .init(color: Palette.ground.opacity(0), location: 0.35),
                            .init(color: Palette.ground, location: 1),
                        ],
                        startPoint: .top, endPoint: .bottom
                    )
                }
                .clipped()
                .accessibilityHidden(true)
            HStack(alignment: .top, spacing: 12) {
                Artwork(url: posterURL, seed: title)
                    .frame(width: 100, height: 150)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .shadow(color: .black.opacity(0.6), radius: 15, y: 12)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    info()
                }
                .padding(.top, 26)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16)
            .padding(.top, topInset + 47)
        }
    }
}

/// The top of Show and Movie on iPad:
/// the backdrop across the page, darker on the left and fading into the
/// ground, the poster at the left and `info` beside it. `portrait` is
/// the portrait layout's: a taller poster lower down, the backdrop
/// darkened only towards the bottom.
struct WideDetailHeader<Info: View>: View {
    let title: String
    let backdropURL: URL?
    let posterURL: URL?
    let topInset: CGFloat
    var portrait = false
    @ViewBuilder var info: () -> Info

    var body: some View {
        let poster = portrait ? CGSize(width: 150, height: 225) : CGSize(width: 140, height: 210)
        ZStack(alignment: .topLeading) {
            Color.clear
                .frame(height: topInset + (portrait ? 246 : 256))
                .frame(maxWidth: .infinity)
                .overlay { Artwork(url: backdropURL, seed: title, maxPixel: 2000) }
                .overlay {
                    RadialGradient(colors: [Palette.gold.opacity(0.10), .clear], center: UnitPoint(x: 0.78, y: 0.1), startRadius: 0, endRadius: 420)
                }
                .overlay {
                    if !portrait {
                        LinearGradient(
                            stops: [
                                .init(color: Palette.ground.opacity(0.85), location: 0),
                                .init(color: Palette.ground.opacity(0.45), location: 0.45),
                                .init(color: Palette.ground.opacity(0), location: 0.8),
                            ],
                            startPoint: .leading, endPoint: .trailing
                        )
                    }
                }
                .overlay {
                    LinearGradient(
                        stops: [
                            .init(color: Palette.ground.opacity(0.4), location: 0),
                            .init(color: Palette.ground.opacity(0), location: portrait ? 0.35 : 0.3),
                            .init(color: Palette.ground, location: 1),
                        ],
                        startPoint: .top, endPoint: .bottom
                    )
                }
                .clipped()
                .accessibilityHidden(true)
            HStack(alignment: .top, spacing: 24) {
                Artwork(url: posterURL, seed: title)
                    .frame(width: poster.width, height: poster.height)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .shadow(color: .black.opacity(0.6), radius: 20, y: 16)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 8) {
                    info()
                }
                .padding(.top, 6)
                .frame(maxWidth: 720, alignment: .leading)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, PadLayout.margin)
            .padding(.top, topInset + (portrait ? 76 : 36))
        }
    }
}

/// The fixed-width iPad detail column: art stays behind the poster and facts,
/// while the column's own scroll view can reveal actions, overview and cast.
struct DetailColumnHeader<Info: View>: View {
    let title: String
    let backdropURL: URL?
    let posterURL: URL?
    @ViewBuilder var info: () -> Info

    var body: some View {
        HStack(alignment: .bottom, spacing: 16) {
            Artwork(url: posterURL, seed: title)
                .frame(width: 150, height: 225)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .shadow(color: .black.opacity(0.5), radius: 16, y: 10)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) { info() }
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(PadLayout.margin)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            Artwork(url: backdropURL, seed: title, maxPixel: 1200)
                .overlay {
                    LinearGradient(colors: [Palette.ground.opacity(0.35), Palette.ground], startPoint: .top, endPoint: .bottom)
                }
                .clipped()
                .accessibilityHidden(true)
        }
    }
}

/// The big title beside the poster: 26 pt on iPhone, 36 on iPad.
struct DetailTitle: View {
    let text: String
    var size: CGFloat = 26

    var body: some View {
        Text(text)
            .scaledFont(size, weight: .heavy)
            .tracking(-size / 50)
            .foregroundStyle(Palette.text)
            .lineLimit(3)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }
}

/// An overview: two lines (or `lines`), the rest on tap.
struct OverviewText: View {
    let text: String
    var lines = 2
    var size: CGFloat = 14
    @State private var expanded = false

    var body: some View {
        Button {
            withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
        } label: {
            Text(text)
                .scaledFont(size)
                .lineSpacing(3)
                .foregroundStyle(Palette.textSecondary)
                .multilineTextAlignment(.leading)
                .lineLimit(expanded ? nil : lines)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .accessibilityHint(expanded ? "Shows less" : "Shows all of it")
    }
}

/// "Continuing · next Nov 4" in gold, or "Ended" in grey.
struct StatusPillView: View {
    let pill: StatusPill

    var body: some View {
        let color = pill.active ? Palette.gold : Palette.muted
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(pill.text).scaledFont(11, weight: .semibold)
        }
        .foregroundStyle(color)
        .padding(.horizontal, 9)
        .padding(.vertical, 3)
        .overlay(Capsule().strokeBorder(color))
        .accessibilityElement(children: .combine)
    }
}

/// A heading inside a detail page ("Versions", "Tracks · 4K Dolby Vision").
struct DetailHeading: View {
    let title: String
    var detail: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(title).scaledFont(17, weight: .semibold).foregroundStyle(Palette.text)
            if let detail {
                Text("· \(detail)").scaledFont(12, weight: .medium).foregroundStyle(Palette.muted)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// Cast as a sideways row of faces, names and roles, inset by `margin`.
/// `large` is the iPad's: bigger faces and type. From API level 34 the
/// server says who each person is, so the row is the cast, then the crew
/// not already in it (`crew`, a movie's), each a button that opens their
/// movies and shows in the library (``PersonScreen``), on the page's own
/// server. Without that, the cast is names and faces that take no taps.
struct CastRow: View {
    let cast: [Person]
    var crew: [Person] = []
    var margin: CGFloat = 16
    var large = false
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        let people = CastPerson.row(cast: cast, crew: crew)
        if !people.isEmpty {
            row(title: people.contains(where: \.isCrew) ? "Cast and crew" : "Cast") {
                ForEach(people) { person in
                    Button { navigator.open(person.route, on: services.library.serverScope) } label: {
                        face(name: person.name, role: person.role, path: person.profilePath)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(CardButtonStyle())
                    .accessibilityLabel(person.accessibilityLabel)
                    .accessibilityHint("Opens their movies and shows")
                    .accessibilityIdentifier("cast-person-\(person.personID)")
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("title-people")
        } else if !cast.isEmpty {
            row(title: "Cast") {
                ForEach(cast, id: \.self) { person in
                    face(name: person.name, role: person.role, path: person.profilePath)
                        .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private func row<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            DetailHeading(title: title).padding(.horizontal, margin)
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: large ? 14 : 10) { content() }
            }
            .contentMargins(.horizontal, margin, for: .scrollContent)
            .stopsAtVerticalBar()
            .scrollIndicators(.hidden)
        }
    }

    private func face(name: String, role: String?, path: String?) -> some View {
        VStack(spacing: large ? 6 : 5) {
            PersonFace(name: name, url: services.library.imageURL(path, size: .w185), size: large ? 64 : 52)
            Text(name)
                .scaledFont(large ? 13 : 11, weight: .semibold)
                .foregroundStyle(Palette.text)
            if let role {
                Text(role).scaledFont(large ? 12 : 10).foregroundStyle(Palette.muted)
            }
        }
        .multilineTextAlignment(.center)
        .frame(width: large ? 96 : 82)
    }
}

/// Label and value rows ("Released  Sep 26, 2026").
struct DetailRows: View {
    let rows: [DetailRow]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(row.label).scaledFont(13).foregroundStyle(Palette.muted).frame(width: 84, alignment: .leading)
                    Text(row.value).scaledFont(13).foregroundStyle(Palette.text)
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 8)
                .overlay(alignment: .bottom) { if index < rows.count - 1 { RowSeparator() } }
                .accessibilityElement(children: .combine)
            }
        }
    }
}
