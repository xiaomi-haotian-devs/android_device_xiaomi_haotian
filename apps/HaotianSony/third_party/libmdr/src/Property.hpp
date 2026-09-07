#pragma once

#include <mdr/Protocol.hpp>

namespace mdr
{
    template <typename T>
    struct MDRProperty
    {
        T desired{};
        T current{};
        T submitted{};
        uint64_t revision{};
        uint64_t submittedRevision{};
        bool reported{}; // Device response, not a local ACK/commit or zero initialization.

        void stage(const T& value)
        {
            desired = value;
            ++revision;
        }

        void stage(T&& value)
        {
            desired = std::move(value);
            ++revision;
        }

        void overwrite(const T& value)
        {
            reported = true;
            current = value;
            if (revision == submittedRevision)
                desired = value;
        }

        void submit()
        {
            submitted = desired;
            submittedRevision = revision;
        }

        [[nodiscard]] constexpr bool dirty() const noexcept { return desired != current; }
        [[nodiscard]] constexpr bool pending() const noexcept { return submitted != current; }

        void commit()
        {
            current = submitted;
            if (revision == submittedRevision)
                desired = submitted;
        }

        void override(const T& value)
        {
            reported = true;
            current = value;
            if (revision == submittedRevision)
                desired = value;
            submitted = value;
        }
    };
} // namespace mdr
