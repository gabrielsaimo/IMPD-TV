import SwiftUI

/// Everything about the church on one screen, with nothing to scroll or
/// navigate into: a viewer presses up, reads, and presses up again.
struct ChurchInfoPanel: View {
    let info: ChurchInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Divider()
                .overlay(.white.opacity(0.2))
                .padding(.vertical, 28)
            details(for: info)
        }
        .padding(.horizontal, 96)
        .padding(.vertical, 72)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black.opacity(0.96))
        .ignoresSafeArea()
    }

    private var header: some View {
        HStack(spacing: 28) {
            VStack(alignment: .leading, spacing: 6) {
                Text(ChurchInfo.name)
                    .font(.system(size: 52, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                Text(ChurchInfo.tagline)
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .foregroundStyle(Color(red: 0.29, green: 0.50, blue: 0.83))
            }
            Spacer()
            Text("▲ para voltar")
                .font(.system(size: 26, weight: .semibold, design: .rounded))
                .foregroundStyle(.white.opacity(0.6))
        }
    }

    private func details(for info: ChurchInfo) -> some View {
        HStack(alignment: .top, spacing: 56) {
            hero
                .frame(width: 380)
                .frame(maxHeight: .infinity)

            VStack(alignment: .leading, spacing: 12) {
                label("SOBRE")
                Text(ChurchInfo.about)
                    .font(.system(size: 28, weight: .regular, design: .rounded))
                    .foregroundStyle(.white)
                    .lineSpacing(6)
                Spacer(minLength: 0)
            }

            VStack(alignment: .leading, spacing: 12) {
                label("SEDE")
                Text(info.headOfficeAddress)
                    .font(.system(size: 28, weight: .regular, design: .rounded))
                    .foregroundStyle(.white)

                label("CONTATO").padding(.top, 12)
                Text(info.email)
                    .font(.system(size: 28, weight: .regular, design: .rounded))
                    .foregroundStyle(.white)

                Spacer(minLength: 16)

                label("SITE OFICIAL")
                Text(ChurchInfo.site)
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text("Mais de \(info.congregationCount) igrejas no Brasil")
                    .font(.system(size: 26, weight: .regular, design: .rounded))
                    .foregroundStyle(.white.opacity(0.6))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var hero: some View {
        RoundedRectangle(cornerRadius: 24, style: .continuous)
            .fill(Color.white.opacity(0.08))
            .overlay {
                // scaledToFit, not Fill: the source photo is 306x267 and
                // cropping it to a tall card would blow it up past twice its
                // own size and cut the sides off.
                AsyncImage(url: info.heroImageURL) { image in
                    image.resizable().scaledToFit().padding(16)
                } placeholder: {
                    Image(systemName: "person.crop.square")
                        .font(.system(size: 96))
                        .foregroundStyle(.white.opacity(0.25))
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 24, weight: .bold, design: .rounded))
            .foregroundStyle(Color(red: 0.29, green: 0.50, blue: 0.83))
    }
}
